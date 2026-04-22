package az.testup.scheduler;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.entity.PayriffOrder;
import az.testup.entity.SubscriptionUsage;
import az.testup.entity.UserSubscription;
import az.testup.repository.PayriffOrderRepository;
import az.testup.repository.SubscriptionUsageRepository;
import az.testup.repository.UserSubscriptionRepository;
import az.testup.service.ExamService;
import az.testup.service.PayriffService;
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

    private final PayriffOrderRepository payriffOrderRepository;
    private final PayriffService payriffService;
    private final UserSubscriptionService userSubscriptionService;
    private final ExamService examService;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final SubscriptionUsageRepository subscriptionUsageRepository;
    private final SubmissionService submissionService;

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

                String paymentStatus = payriffService.getOrderStatus(order.getOrderId());
                boolean isPaid = isPaidStatus(paymentStatus);

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
                        req.setPaymentProvider("KAPITALBANK");
                        req.setTransactionId(order.getOrderId());
                        double economicValue = order.getDurationDays() * (order.getPlan().getPrice() / 30.0);
                        req.setAmountPaid(economicValue);
                        userSubscriptionService.assignSubscription(req);
                        log.info("Payment recovery: subscription activated for orderId={}", order.getOrderId());
                    }
                } else if (isFailedStatus(paymentStatus)) {
                    order.setStatus("FAILED");
                    payriffOrderRepository.save(order);
                    log.info("Payment recovery: order marked FAILED, orderId={}, status={}", order.getOrderId(), paymentStatus);
                } else {
                    // Still pending at Kapital Bank — roll back so we retry next cycle
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
     * Auto-submits in-progress exams whose duration has elapsed (e.g. student closed browser).
     */
    @Scheduled(fixedDelay = 10_000)
    public void autoSubmitExpiredExams() {
        submissionService.autoSubmitExpiredExams();
    }

    /**
     * Runs at 00:00 on the 1st of every month.
     * Creates fresh SubscriptionUsage records (monthly counters = 0) for all active subscriptions.
     * Also cleans up usage records older than 3 months.
     */
    @Scheduled(cron = "0 0 0 1 * *")
    @Transactional
    public void resetMonthlyUsage() {
        String currentMonthYear = YearMonth.now().toString(); // e.g. "2026-04"
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

        // Keep only the last 3 months of usage records
        String cutoff = YearMonth.now().minusMonths(3).toString();
        subscriptionUsageRepository.deleteByMonthYearBefore(cutoff);

        log.info("Monthly usage reset: completed for month {}", currentMonthYear);
    }
}
