package az.testup.repository;

import az.testup.entity.SubscriptionPlanPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionPlanPriceRepository extends JpaRepository<SubscriptionPlanPrice, Long> {

    Optional<SubscriptionPlanPrice> findByPlanIdAndDurationMonths(Long planId, Integer durationMonths);

    List<SubscriptionPlanPrice> findByPlanId(Long planId);

    List<SubscriptionPlanPrice> findByPlanIdAndVisibleTrue(Long planId);

    List<SubscriptionPlanPrice> findByVisibleTrue();

    void deleteByPlanId(Long planId);
}
