package az.testup.repository;

import az.testup.entity.SubscriptionUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubscriptionUsageRepository extends JpaRepository<SubscriptionUsage, Long> {
    Optional<SubscriptionUsage> findByUserSubscriptionIdAndMonthYear(Long userSubscriptionId, String monthYear);
}
