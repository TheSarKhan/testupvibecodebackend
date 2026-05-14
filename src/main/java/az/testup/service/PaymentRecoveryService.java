package az.testup.service;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.entity.PaymentOrder;
import az.testup.entity.UserSubscription;
import az.testup.enums.AuditAction;
import az.testup.repository.PaymentOrderRepository;
import az.testup.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Processes a single abandoned payment order in its own transaction.
 * Lives in a separate Spring bean so @Transactional(REQUIRES_NEW) goes through
 * the proxy when called from {@link az.testup.scheduler.SubscriptionScheduler}.
 * Without the proxy boundary, the REQUIRES_NEW propagation would be ignored
 * and a single failed order would roll back all sibling recoveries.
 */
@Service
@RequiredArgsConstructor
public class PaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);

    private final PaymentOrderRepository paymentOrderRepository;
    private final KapitalBankService kapitalBankService;
    private final UserSubscriptionService userSubscriptionService;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final ExamService examService;
    private final AuditLogService auditLogService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processOneStuckOrder(String orderId) {
        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId).orElse(null);
        if (order == null || "PAID".equals(order.getStatus()) || "FAILED".equals(order.getStatus())) {
            return;
        }

        LocalDateTime staleProcessingCutoff = LocalDateTime.now().minusMinutes(2);

        // Reclaim PENDING orders normally; also reclaim PROCESSING orders whose
        // processor crashed mid-activation. Without the PROCESSING branch,
        // claimForProcessing always returns 0 for them and they'd be skipped forever.
        int claimed;
        if ("PROCESSING".equals(order.getStatus())) {
            claimed = paymentOrderRepository.reclaimStaleOrder(order.getOrderId(), staleProcessingCutoff);
        } else {
            claimed = paymentOrderRepository.claimForProcessing(order.getOrderId());
        }
        if (claimed == 0) {
            return;
        }

        String paymentStatus = kapitalBankService.getOrderStatus(order.getOrderId());
        boolean isPaid = isPaidStatus(paymentStatus);

        if (isPaid) {
            // markAsPaidIfNotAlready provides idempotency — if a concurrent process
            // already activated this order we skip the side-effects entirely to
            // avoid doubling subscription duration via the RENEWAL branch.
            int activated = paymentOrderRepository.markAsPaidIfNotAlready(order.getOrderId());
            if (activated == 0) {
                log.warn("Payment recovery: order {} already PAID — skipping duplicate activation", order.getOrderId());
                return;
            }
            order.setStatus("PAID");

            String target;
            if (order.getExam() != null) {
                examService.purchaseExam(order.getExam().getShareLink(), order.getUser(),
                        java.math.BigDecimal.valueOf(order.getAmount()));
                log.info("Payment recovery: exam purchase activated for orderId={}", order.getOrderId());
                target = "İmtahan: " + order.getExam().getTitle();
            } else if (order.getPlan() != null) {
                AssignSubscriptionRequest req = new AssignSubscriptionRequest();
                req.setUserId(order.getUser().getId());
                req.setPlanId(order.getPlan().getId());
                req.setDurationMonths(order.getMonths());
                req.setDurationDays(order.getDurationDays());
                req.setPaymentProvider("KAPITALBANK");
                req.setTransactionId(order.getOrderId());
                double economicValue = order.getDurationDays() * (order.getPlan().getPrice() / 30.0);
                req.setAmountPaid(economicValue);
                userSubscriptionService.assignSubscription(req);
                log.info("Payment recovery: subscription activated for orderId={}", order.getOrderId());
                target = order.getPlan().getName();
            } else {
                target = "?";
            }
            auditLogService.log(AuditAction.PAYMENT_AUTO_RECOVERED,
                    "system@scheduler", "Scheduler",
                    "PAYMENT", target,
                    "İstifadəçi: " + order.getUser().getEmail()
                            + ", OrderId: " + order.getOrderId()
                            + ", Məbləğ: " + String.format("%.2f", order.getAmount()) + " AZN");
        } else if (isFailedStatus(paymentStatus)) {
            order.setStatus("FAILED");
            paymentOrderRepository.save(order);
            log.info("Payment recovery: order marked FAILED, orderId={}, status={}", order.getOrderId(), paymentStatus);
            String target = order.getExam() != null ? "İmtahan: " + order.getExam().getTitle()
                    : (order.getPlan() != null ? order.getPlan().getName() : "?");
            auditLogService.log(AuditAction.PAYMENT_FAILED,
                    "system@scheduler", "Scheduler",
                    "PAYMENT", target,
                    "Avtomatik yoxlama: " + paymentStatus
                            + ", OrderId: " + order.getOrderId()
                            + ", İstifadəçi: " + order.getUser().getEmail());
        } else if (isReversedStatus(paymentStatus)) {
            // Reversal AFTER capture (refund / chargeback). Revoke any subscription
            // the payment activated. Use FAILED as the persisted status because the
            // chk_payment_orders_status constraint only allows the canonical set.
            order.setStatus("FAILED");
            paymentOrderRepository.save(order);
            UserSubscription sub = userSubscriptionRepository.findByTransactionId(order.getOrderId()).orElse(null);
            if (sub != null && sub.isActive()) {
                sub.setActive(false);
                userSubscriptionRepository.save(sub);
            }
            String target = order.getExam() != null ? "İmtahan: " + order.getExam().getTitle()
                    : (order.getPlan() != null ? order.getPlan().getName() : "?");
            auditLogService.log(AuditAction.PAYMENT_REVERSED,
                    "system@scheduler", "Scheduler",
                    "PAYMENT", target,
                    "KB status: " + paymentStatus + ", OrderId: " + order.getOrderId()
                            + ", İstifadəçi: " + order.getUser().getEmail()
                            + (sub != null ? ", Abunəlik deaktiv edildi" : ", Abunəlik tapılmadı"));
        } else {
            // Status still unknown — revert the claim so future cycles can retry.
            order.setStatus("PENDING");
            paymentOrderRepository.save(order);
        }
    }

    private boolean isPaidStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.toUpperCase().replace(" ", "").replace("_", "");
        // PARTIALLYPAID intentionally excluded — see PaymentController.isPaidStatus.
        return s.equals("FULLYPAID")
                || s.equals("AUTHORIZED") || s.equals("FUNDED")
                || s.equals("PAID") || s.equals("APPROVED") || s.equals("SUCCESS")
                || s.equals("CONFIRMED") || s.equals("COMPLETE") || s.equals("COMPLETED");
    }

    private boolean isFailedStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.toUpperCase().replace(" ", "").replace("_", "");
        return s.equals("DECLINED") || s.equals("FAILED") || s.equals("CANCELLED")
                || s.equals("REJECTED") || s.equals("REFUSED") || s.equals("EXPIRED")
                || s.equals("VOIDED") || s.equals("CLOSED");
    }

    private boolean isReversedStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.toUpperCase().replace(" ", "").replace("_", "");
        return s.equals("REVERSED") || s.equals("REFUNDED")
                || s.equals("CHARGEBACK") || s.equals("CHARGEDBACK")
                || s.equals("RETURNED");
    }
}
