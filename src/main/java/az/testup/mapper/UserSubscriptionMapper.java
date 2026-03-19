package az.testup.mapper;

import az.testup.dto.response.UserSubscriptionResponse;
import az.testup.entity.UserSubscription;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = {SubscriptionPlanMapper.class})
public interface UserSubscriptionMapper {

    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "userEmail", source = "user.email")
    UserSubscriptionResponse toResponse(UserSubscription entity);
}
