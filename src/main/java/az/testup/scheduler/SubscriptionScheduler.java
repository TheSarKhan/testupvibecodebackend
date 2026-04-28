package az.testup.scheduler;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.entity.PaymentOrder;
import az.testup.entity.SubscriptionUsage;
import az.testup.entity.UserSubscription;
import az.testup.repository.PaymentOrderRepository;
import az.testup.repository.SubscriptionUsageRepository;
import az.testup.repository.UserSubscriptionRepository;
import az.testup.service.ExamService;
import az.testup.service.KapitalBankService;
import az.testup.service.SubmissionService;
import az.testup.service.UserSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SubscriptionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionScheduler.class);

    private final PaymentOrderRepository paymentOrderRepository;
    private final KapitalBankService kapitalBankService;
    private final UserSubscriptionService userSubscriptionService;
    private final ExamService examService;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final SubscriptionUsageRepository subscriptionUsageRepository;
    private final SubmissionService submissionService;

    /**
     * Runs every 10 minutes. Picks up orders stuck in PENDING or PROCESSING
     * (e.g. user paid but closed the browser before verify completed).
     * Checks real status with Kapital Bank and activates or marks them failed.
     */
    @Scheduled(fixedDelay = 600_000)
    @Transactional
    public void recoverAbandonedPayments() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        List<PaymentOrder> stuckOrders = paymentOrderRepository.findStuckOrders(cutoff);

        if (stuckOrders.isEmpty()) return;

        log.info("Payment recovery: checking {} stuck order(s)", stuckOrders.size());

        for (PaymentOrder order : stuckOrders) {
            try {
                int claimed = paymentOrderRepository.claimForProcessing(order.getOrderId());
                if (claimed == 0) {
                    continue;
                }

                String paymentStatus = kapitalBankService.getOrderStatus(order.getOrderId());
                boolean isPaid = isPaidStatus(paymentStatus);

                if (isPaid) {
                    order.setStatus("PAID");
                    paymentOrderRepository.save(order);

                    if (order.getExam() != null) {
                        examService.purchaseExam(order.getExam().getShareLink(), order.getUser());
                        log.info("Payment recovery: exam purchase activated for orderId={}", order.getOrderId());
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
                    }
                } else if (isFailedStatus(paymentStatus)) {
                    order.setStatus("FAILED");
                    paymentOrderRepository.save(order);
                    log.info("Payment recovery: order marked FAILED, orderId={}, status={}", order.getOrderId(), paymentStatus);
                } else {
                    order.setStatus("PENDING");
                    paymentOrderRepository.save(order);
                }
            } catch (Exception e) {
                log.error("Payment recovery: error processing orderId={}: {}", order.getOrderId(), e.getMessage());
                order.setStatus("PENDING");
                paymentOrderRepository.save(order);
            }
        }
    }

    private boolean isPaidStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.toUpperCase().replace(" ", "").replace("_", "");
        return s.equals("FULLYPAID") || s.equals("PARTIALLYPAID")
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

    /**
     * Runs every 10 seconds.
     * Auto-submits in-progress exams whose duration has elapsed.
     */
    @Scheduled(fixedDelay = 10_000)
    public void autoSubmitExpiredExams() {
        submissionService.autoSubmitExpiredExams();
    }

    /**
     * Runs at 00:00 on the 1st of every month.
     * Creates fresh SubscriptionUsage records for all active subscriptions.
     */
    @Scheduled(cron = "0 0 0 1 * *")
    @Transactional
    public void resetMonthlyUsage() {
        String currentMonthYear = YearMonth.now().toString();
        List<UserSubscription> activeSubscriptions = userSubscriptionRepository.findAllActiveSubscriptions();

        log.info("Monthly usage reset: resetting counters for {} active subscription(s) for month {}",
                activeSubscriptions.size(), currentMonthYear);

        for (UserSubscription subscription : activeSubscriptions) {
            boolean exists = subscriptionUsageRepository
                    .findByUserSubscriptionIdAndMonthYear(subscription.getId(), currentMonthYear)
                    .isPresent();
            if (!exists) {
                subscriptionUsageRepository.save(
                        SubscriptionUsage.builder()
                                .userSubscription(subscription)
                                .monthYear(currentMonthYear)
                                .usedMonthlyExams(0)
                                .usedSavedExams(0)
                                .usedAiQuestions(0)
                                .build()
                );
            }
        }

        String cutoff = YearMonth.now().minusMonths(3).toString();
        subscriptionUsageRepository.deleteByMonthYearBefore(cutoff);

        log.info("Monthly usage reset: completed for month {}", currentMonthYear);
    }
}
