package az.testup.service;

import az.testup.dto.request.SubscriptionPlanRequest;
import az.testup.dto.response.SubscriptionPlanResponse;
import az.testup.entity.SubscriptionPlan;
import az.testup.mapper.SubscriptionPlanMapper;
import az.testup.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubscriptionPlanService {

    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final SubscriptionPlanMapper subscriptionPlanMapper;

    public List<SubscriptionPlanResponse> getAllPlans() {
        return subscriptionPlanRepository.findAll()
                .stream()
                .map(subscriptionPlanMapper::toResponse)
                .collect(Collectors.toList());
    }

    public SubscriptionPlanResponse getPlanById(Long id) {
        SubscriptionPlan plan = subscriptionPlanRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Subscription plan not found: " + id));
        return subscriptionPlanMapper.toResponse(plan);
    }

    @Transactional
    public SubscriptionPlanResponse createPlan(SubscriptionPlanRequest request) {
        if (subscriptionPlanRepository.findByName(request.getName()).isPresent()) {
            throw new RuntimeException("Plan with name '" + request.getName() + "' already exists");
        }
        SubscriptionPlan plan = subscriptionPlanMapper.toEntity(request);
        SubscriptionPlan savedPlan = subscriptionPlanRepository.save(plan);
        return subscriptionPlanMapper.toResponse(savedPlan);
    }

    @Transactional
    public SubscriptionPlanResponse updatePlan(Long id, SubscriptionPlanRequest request) {
        SubscriptionPlan plan = subscriptionPlanRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Subscription plan not found: " + id));
        
        subscriptionPlanMapper.updateEntityFromRequest(request, plan);
        SubscriptionPlan updatedPlan = subscriptionPlanRepository.save(plan);
        return subscriptionPlanMapper.toResponse(updatedPlan);
    }

    @Transactional
    public void deletePlan(Long id) {
        if (!subscriptionPlanRepository.existsById(id)) {
            throw new RuntimeException("Subscription plan not found: " + id);
        }
        subscriptionPlanRepository.deleteById(id);
    }
}
