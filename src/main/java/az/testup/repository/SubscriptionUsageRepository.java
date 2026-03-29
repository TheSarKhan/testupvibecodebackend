package az.testup.repository;

import az.testup.entity.SubscriptionUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubscriptionUsageRepository extends JpaRepository<SubscriptionUsage, Long> {
    Optional<SubscriptionUsage> findByUserSubscriptionIdAndMonthYear(Long userSubscriptionId, String monthYear);

    /** Delete usage records older than the given monthYear string (e.g. "2026-01") for cleanup. */
    @Modifying
    @Query("DELETE FROM SubscriptionUsage u WHERE u.monthYear < :beforeMonthYear")
    void deleteByMonthYearBefore(String beforeMonthYear);
}
