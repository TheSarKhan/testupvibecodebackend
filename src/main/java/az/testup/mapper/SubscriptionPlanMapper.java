package az.testup.mapper;

import az.testup.dto.request.SubscriptionPlanRequest;
import az.testup.dto.response.SubscriptionPlanResponse;
import az.testup.entity.SubscriptionPlan;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface SubscriptionPlanMapper {

    SubscriptionPlan toEntity(SubscriptionPlanRequest request);

    SubscriptionPlanResponse toResponse(SubscriptionPlan entity);

    void updateEntityFromRequest(SubscriptionPlanRequest request, @MappingTarget SubscriptionPlan entity);
}
