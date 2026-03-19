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
        Optional<UserSubscription> sub = userSubscriptionRepository.findActiveSubscriptionByUserIdAndDate(userId, LocalDateTime.now());
        return sub.map(entity -> {
            UserSubscriptionResponse response = userSubscriptionMapper.toResponse(entity);
            
            // Populate current month usage
            String currentMonthYear = YearMonth.now().toString();
            int usage = subscriptionUsageRepository.findByUserSubscriptionIdAndMonthYear(entity.getId(), currentMonthYear)
                    .map(SubscriptionUsage::getUsedMonthlyExams)
                    .orElse(0);
            response.setUsedMonthlyExams(usage);
            
            // Populate total exams count
            long totalExams = examRepository.countByTeacherId(entity.getUser().getId());
            response.setTotalExamsCount(totalExams);
            
            return response;
        }).orElse(null);
    }

    @Transactional
    public UserSubscriptionResponse assignSubscription(AssignSubscriptionRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found: " + request.getUserId()));

        SubscriptionPlan plan = subscriptionPlanRepository.findById(request.getPlanId())
                .orElseThrow(() -> new RuntimeException("Plan not found: " + request.getPlanId()));

        Optional<UserSubscription> currentActiveOpt = userSubscriptionRepository
                .findActiveSubscriptionByUserIdAndDate(user.getId(), LocalDateTime.now());

        currentActiveOpt.ifPresent(currentActive -> {
            currentActive.setActive(false);
            userSubscriptionRepository.save(currentActive);
        });

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endDate = now.plusMonths(request.getDurationMonths());

        UserSubscription userSubscription = UserSubscription.builder()
                .user(user)
                .plan(plan)
                .startDate(now)
                .endDate(endDate)
                .isActive(true)
                .paymentProvider(request.getPaymentProvider() != null ? request.getPaymentProvider() : "MANUAL")
                .transactionId(request.getTransactionId())
                .build();

        userSubscription = userSubscriptionRepository.save(userSubscription);

        return userSubscriptionMapper.toResponse(userSubscription);
    }

    @Transactional
    public void cancelSubscription(Long subscriptionId) {
        UserSubscription userSubscription = userSubscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found: " + subscriptionId));
        
        userSubscription.setActive(false);
        userSubscriptionRepository.save(userSubscription);
    }
}
