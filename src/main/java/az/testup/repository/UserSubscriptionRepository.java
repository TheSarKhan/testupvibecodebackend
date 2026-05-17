package az.testup.repository;

import az.testup.entity.UserSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {

    @Query("SELECT us FROM UserSubscription us WHERE us.user.id = :userId AND us.isActive = true AND us.startDate <= current_timestamp AND us.endDate >= current_timestamp")
    Optional<UserSubscription> findActiveSubscriptionByUserId(Long userId);

    // Fallback if current_timestamp doesn't match perfectly with local Java timezone:
    @Query("SELECT us FROM UserSubscription us WHERE us.user.id = :userId AND us.isActive = true AND us.startDate <= :now AND us.endDate >= :now")
    Optional<UserSubscription> findActiveSubscriptionByUserIdAndDate(Long userId, LocalDateTime now);

    void deleteByUserId(Long userId);

    @Query("SELECT COUNT(us) FROM UserSubscription us WHERE us.isActive = true AND us.endDate >= current_timestamp")
    long countActiveSubscriptions();

    @Query("SELECT us FROM UserSubscription us WHERE us.isActive = true AND us.endDate >= current_timestamp")
    List<UserSubscription> findAllActiveSubscriptions();

    /**
     * Bulk-deactivate every is_active=true row for this user.
     * Used before inserting a new active subscription to satisfy the
     * uq_user_subscriptions_one_active_per_user unique index without
     * relying on Hibernate's flush ordering (INSERT-before-UPDATE default).
     */
    @Modifying
    @Query("UPDATE UserSubscription us SET us.isActive = false WHERE us.user.id = :userId AND us.isActive = true")
    int deactivateAllForUser(Long userId);
}
