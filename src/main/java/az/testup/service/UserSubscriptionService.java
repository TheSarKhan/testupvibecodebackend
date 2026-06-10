package az.testup.service;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.dto.response.UserSubscriptionResponse;
import az.testup.entity.SubscriptionPlan;
import az.testup.entity.SubscriptionUsage;
import az.testup.entity.User;
import az.testup.entity.UserSubscription;
import az.testup.exception.ResourceNotFoundException;
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
            // BUG-24: usage is keyed by the subscription's own 30-day period,
            // not the calendar month.
            String periodKey = SubscriptionValidatorService.currentPeriodKey(entity);
            int usage = subscriptionUsageRepository
                    .findByUserSubscriptionIdAndMonthYear(entity.getId(), periodKey)
                    .map(SubscriptionUsage::getUsedMonthlyExams)
                    .orElse(0);
            response.setUsedMonthlyExams(usage);
            long totalExams = examRepository.countByTeacherId(entity.getUser().getId());
            response.setTotalExamsCount(totalExams);
            response.setUsageResetsAt(SubscriptionValidatorService.nextUsageResetAt(entity));
            return response;
        }).orElse(null);
    }

    @Transactional
    public UserSubscriptionResponse assignSubscription(AssignSubscriptionRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("İstifadəçi tapılmadı"));

        SubscriptionPlan newPlan = subscriptionPlanRepository.findById(request.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("Abunəlik planı tapılmadı"));

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
            if (request.getDurationMonths() != null && request.getDurationMonths() > 0) {
                current.setDurationMonths(request.getDurationMonths());
            }
            // BUG-24: a paid renewal starts a fresh usage cycle IMMEDIATELY —
            // re-anchoring changes the current period key, so the counters
            // restart at zero instead of waiting for the 1st of next month.
            current.setUsageAnchor(now);
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

        Integer subDurationMonths = request.getDurationMonths() != null && request.getDurationMonths() > 0
                ? request.getDurationMonths() : null;
        return save(user, newPlan, now, now.plusDays(durationDays), provider, txId, amountPaid, subDurationMonths);
    }

    private UserSubscriptionResponse save(User user, SubscriptionPlan plan,
                                           LocalDateTime start, LocalDateTime end,
                                           String provider, String txId, double amountPaid,
                                           Integer durationMonths) {
        UserSubscription sub = UserSubscription.builder()
                .user(user)
                .plan(plan)
                .startDate(start)
                .endDate(end)
                .isActive(true)
                .paymentProvider(provider)
                .transactionId(txId)
                .amountPaid(amountPaid)
                .durationMonths(durationMonths)
                .usageAnchor(start) // BUG-24: usage cycle starts with the subscription
                .build();
        return userSubscriptionMapper.toResponse(userSubscriptionRepository.save(sub));
    }

    @Transactional
    public void cancelSubscription(Long subscriptionId) {
        UserSubscription sub = userSubscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Abunəlik tapılmadı"));
        sub.setActive(false);
        userSubscriptionRepository.save(sub);
    }
}
