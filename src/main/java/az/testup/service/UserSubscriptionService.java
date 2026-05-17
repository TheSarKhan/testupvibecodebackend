package az.testup.service;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.dto.response.UserSubscriptionResponse;
import az.testup.entity.SubscriptionPlan;
import az.testup.entity.SubscriptionUsage;
import az.testup.entity.User;
import az.testup.entity.UserSubscription;
import az.testup.mapper.UserSubscriptionMapper;
import az.testup.repository.ExamRepository;
import az.testup.repository.SubscriptionPlanRepository;
import az.testup.repository.SubscriptionUsageRepository;
import az.testup.repository.UserRepository;
import az.testup.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserSubscriptionService {

    private final UserSubscriptionRepository userSubscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final SubscriptionUsageRepository subscriptionUsageRepository;
    private final ExamRepository examRepository;
    private final UserRepository userRepository;
    private final UserSubscriptionMapper userSubscriptionMapper;

    public List<UserSubscriptionResponse> getAllSubscriptions() {
        return userSubscriptionRepository.findAll()
                .stream()
                .map(userSubscriptionMapper::toResponse)
                .collect(Collectors.toList());
    }

    public UserSubscriptionResponse getActiveSubscription(Long userId) {
        Optional<UserSubscription> sub = userSubscriptionRepository
                .findActiveSubscriptionByUserIdAndDate(userId, LocalDateTime.now());
        return sub.map(entity -> {
            UserSubscriptionResponse response = userSubscriptionMapper.toResponse(entity);
            String currentMonthYear = YearMonth.now().toString();
            int usage = subscriptionUsageRepository
                    .findByUserSubscriptionIdAndMonthYear(entity.getId(), currentMonthYear)
                    .map(SubscriptionUsage::getUsedMonthlyExams)
                    .orElse(0);
            response.setUsedMonthlyExams(usage);
            long totalExams = examRepository.countByTeacherId(entity.getUser().getId());
            response.setTotalExamsCount(totalExams);
            return response;
        }).orElse(null);
    }

    @Transactional
    public UserSubscriptionResponse assignSubscription(AssignSubscriptionRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found: " + request.getUserId()));

        SubscriptionPlan newPlan = subscriptionPlanRepository.findById(request.getPlanId())
                .orElseThrow(() -> new RuntimeException("Plan not found: " + request.getPlanId()));

        int months = request.getDurationMonths() != null ? request.getDurationMonths() : 1;
        long durationDays = request.getDurationDays() != null && request.getDurationDays() > 0
                ? request.getDurationDays()
                : (long) months * 30;
        if (durationDays <= 0) durationDays = 30; // safety: never create a zero/negative-length sub
        String provider = request.getPaymentProvider() != null ? request.getPaymentProvider() : "MANUAL";
        String txId = request.getTransactionId();
        double amountPaid = request.getAmountPaid() != null ? request.getAmountPaid() : 0.0;

        LocalDateTime now = LocalDateTime.now();

        Optional<UserSubscription> currentOpt = userSubscriptionRepository
                .findActiveSubscriptionByUserIdAndDate(user.getId(), now);

        // RENEWAL: same plan as the currently-active subscription → extend end date
        if (currentOpt.isPresent() && currentOpt.get().getPlan().getId().equals(newPlan.getId())) {
            UserSubscription current = currentOpt.get();
            current.setEndDate(current.getEndDate().plusDays(durationDays));
            current.setPaymentProvider(provider);
            current.setTransactionId(txId);
            current.setAmountPaid(current.getAmountPaid() + amountPaid);
            return userSubscriptionMapper.toResponse(userSubscriptionRepository.save(current));
        }

        // NEW SUBSCRIPTION or PLAN SWITCH (any direction):
        // Bulk-deactivate ALL is_active=true rows for this user FIRST and flush —
        // this prevents the uq_user_subscriptions_one_active_per_user unique
        // index from firing when Hibernate flushes the INSERT before the UPDATE.
        // The bulk @Modifying query is executed and flushed by the persistence
        // provider before any pending in-memory changes, satisfying the constraint.
        userSubscriptionRepository.deactivateAllForUser(user.getId());
        userSubscriptionRepository.flush();

        return save(user, newPlan, now, now.plusDays(durationDays), provider, txId, amountPaid);
    }

    private UserSubscriptionResponse save(User user, SubscriptionPlan plan,
                                           LocalDateTime start, LocalDateTime end,
                                           String provider, String txId, double amountPaid) {
        UserSubscription sub = UserSubscription.builder()
                .user(user)
                .plan(plan)
                .startDate(start)
                .endDate(end)
                .isActive(true)
                .paymentProvider(provider)
                .transactionId(txId)
                .amountPaid(amountPaid)
                .build();
        return userSubscriptionMapper.toResponse(userSubscriptionRepository.save(sub));
    }

    @Transactional
    public void cancelSubscription(Long subscriptionId) {
        UserSubscription sub = userSubscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found: " + subscriptionId));
        sub.setActive(false);
        userSubscriptionRepository.save(sub);
    }
}
