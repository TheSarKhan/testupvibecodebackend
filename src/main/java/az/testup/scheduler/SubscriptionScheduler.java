package az.testup.scheduler;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.entity.PayriffOrder;
import az.testup.repository.PayriffOrderRepository;
import az.testup.service.ExamService;
import az.testup.service.PayriffService;
import az.testup.service.UserSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SubscriptionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionScheduler.class);

    private final PayriffOrderRepository payriffOrderRepository;
    private final PayriffService payriffService;
    private final UserSubscriptionService userSubscriptionService;
    private final ExamService examService;

    /**
     * Runs every 10 minutes. Picks up orders stuck in PENDING or PROCESSING
     * (e.g. user paid but closed the browser before the verify call completed).
     * Checks their real status with Payriff and activates or marks them failed.
     */
    @Scheduled(fixedDelay = 600_000)
    @Transactional
    public void recoverAbandonedPayments() {
        // Only look at orders older than 5 minutes to avoid interfering with active sessions
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        List<PayriffOrder> stuckOrders = payriffOrderRepository.findStuckOrders(cutoff);

        if (stuckOrders.isEmpty()) return;

        log.info("Payment recovery: checking {} stuck order(s)", stuckOrders.size());

        for (PayriffOrder order : stuckOrders) {
            try {
                int claimed = payriffOrderRepository.claimForProcessing(order.getOrderId());
                if (claimed == 0) {
                    // Already being processed by a concurrent verify request — skip
                    continue;
                }

                String payriffStatus = payriffService.getOrderStatus(order.getOrderId());
                boolean isPaid = "PAID".equals(payriffStatus)
                        || "APPROVED".equals(payriffStatus)
                        || "SUCCESS".equals(payriffStatus);

                if (isPaid) {
                    order.setStatus("PAID");
                    payriffOrderRepository.save(order);

                    if (order.getExam() != null) {
                        examService.purchaseExam(order.getExam().getShareLink(), order.getUser());
                        log.info("Payment recovery: exam purchase activated for orderId={}", order.getOrderId());
                    } else if (order.getPlan() != null) {
                        AssignSubscriptionRequest req = new AssignSubscriptionRequest();
                        req.setUserId(order.getUser().getId());
                        req.setPlanId(order.getPlan().getId());
                        req.setDurationMonths(order.getMonths());
                        req.setDurationDays(order.getDurationDays());
                        req.setPaymentProvider("PAYRIFF");
                        req.setTransactionId(order.getOrderId());
                        double economicValue = order.getDurationDays() * (order.getPlan().getPrice() / 30.0);
                        req.setAmountPaid(economicValue);
                        userSubscriptionService.assignSubscription(req);
                        log.info("Payment recovery: subscription activated for orderId={}", order.getOrderId());
                    }
                } else if ("DECLINED".equals(payriffStatus)
                        || "FAILED".equals(payriffStatus)
                        || "CANCELLED".equals(payriffStatus)) {
                    order.setStatus("FAILED");
                    payriffOrderRepository.save(order);
                    log.info("Payment recovery: order marked FAILED, orderId={}, payriffStatus={}", order.getOrderId(), payriffStatus);
                } else {
                    // Still genuinely pending at Payriff — roll back so we retry next cycle
                    order.setStatus("PENDING");
                    payriffOrderRepository.save(order);
                }
            } catch (Exception e) {
                log.error("Payment recovery: error processing orderId={}: {}", order.getOrderId(), e.getMessage());
                // Reset to PENDING so next cycle retries
                order.setStatus("PENDING");
                payriffOrderRepository.save(order);
            }
        }
    }
}
