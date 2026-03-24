package az.testup.mapper;

import az.testup.dto.response.UserSubscriptionResponse;
import az.testup.entity.UserSubscription;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UserSubscriptionMapper {

    private final SubscriptionPlanMapper planMapper;

    public UserSubscriptionResponse toResponse(UserSubscription entity) {
        if (entity == null) return null;
        UserSubscriptionResponse r = new UserSubscriptionResponse();
        r.setId(entity.getId());
        if (entity.getUser() != null) {
            r.setUserId(entity.getUser().getId());
            r.setUserEmail(entity.getUser().getEmail());
        }
        r.setPlan(planMapper.toResponse(entity.getPlan()));
        r.setStartDate(entity.getStartDate());
        r.setEndDate(entity.getEndDate());
        r.setActive(entity.isActive());
        r.setPaymentProvider(entity.getPaymentProvider());
        return r;
    }
}
