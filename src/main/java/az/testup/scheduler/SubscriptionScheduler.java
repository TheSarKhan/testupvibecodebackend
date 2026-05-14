package az.testup.scheduler;

import az.testup.entity.PaymentOrder;
import az.testup.entity.SubscriptionUsage;
import az.testup.entity.UserSubscription;
import az.testup.repository.PaymentOrderRepository;
import az.testup.repository.SubscriptionUsageRepository;
import az.testup.repository.UserSubscriptionRepository;
import az.testup.service.PaymentRecoveryService;
import az.testup.service.SubmissionService;
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
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final SubscriptionUsageRepository subscriptionUsageRepository;
    private final SubmissionService submissionService;
    private final PaymentRecoveryService paymentRecoveryService;

    /**
     * Runs every 10 minutes. Picks up orders stuck in PENDING or PROCESSING
     * (e.g. user paid but closed the browser before verify completed).
     * Each order is processed in its own transaction so a failure on one
     * doesn't roll back successful recoveries of siblings.
     */
    @Scheduled(fixedDelay = 600_000)
    public void recoverAbandonedPayments() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        List<PaymentOrder> stuckOrders = paymentOrderRepository.findStuckOrders(cutoff);

        if (stuckOrders.isEmpty()) return;

        log.info("Payment recovery: checking {} stuck order(s)", stuckOrders.size());

        for (PaymentOrder order : stuckOrders) {
            try {
                paymentRecoveryService.processOneStuckOrder(order.getOrderId());
            } catch (Exception e) {
                log.error("Payment recovery: error processing orderId={}: {}", order.getOrderId(), e.getMessage());
            }
        }
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
