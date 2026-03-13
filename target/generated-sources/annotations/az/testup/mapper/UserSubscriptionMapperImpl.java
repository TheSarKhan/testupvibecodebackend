package az.testup.mapper;

import az.testup.dto.response.UserSubscriptionResponse;
import az.testup.entity.User;
import az.testup.entity.UserSubscription;
import javax.annotation.processing.Generated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-13T18:32:22+0400",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class UserSubscriptionMapperImpl implements UserSubscriptionMapper {

    @Autowired
    private SubscriptionPlanMapper subscriptionPlanMapper;

    @Override
    public UserSubscriptionResponse toResponse(UserSubscription entity) {
        if ( entity == null ) {
            return null;
        }

        UserSubscriptionResponse userSubscriptionResponse = new UserSubscriptionResponse();

        userSubscriptionResponse.setUserId( entityUserId( entity ) );
        userSubscriptionResponse.setUserEmail( entityUserEmail( entity ) );
        userSubscriptionResponse.setId( entity.getId() );
        userSubscriptionResponse.setPlan( subscriptionPlanMapper.toResponse( entity.getPlan() ) );
        userSubscriptionResponse.setStartDate( entity.getStartDate() );
        userSubscriptionResponse.setEndDate( entity.getEndDate() );
        userSubscriptionResponse.setActive( entity.isActive() );
        userSubscriptionResponse.setPaymentProvider( entity.getPaymentProvider() );

        return userSubscriptionResponse;
    }

    private Long entityUserId(UserSubscription userSubscription) {
        User user = userSubscription.getUser();
        if ( user == null ) {
            return null;
        }
        return user.getId();
    }

    private String entityUserEmail(UserSubscription userSubscription) {
        User user = userSubscription.getUser();
        if ( user == null ) {
            return null;
        }
        return user.getEmail();
    }
}
