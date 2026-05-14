package az.testup.repository;

import az.testup.entity.UserSubscription;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * Acquires a row-level write lock on the user's active subscription (SELECT ... FOR UPDATE).
     * Used during payment initiation / activation to serialize credit calculation
     * and subscription assignment for the same user, preventing concurrent
     * tabs from double-spending the same prorated credit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT us FROM UserSubscription us WHERE us.user.id = :userId AND us.isActive = true AND us.startDate <= :now AND us.endDate >= :now")
    Optional<UserSubscription> findActiveSubscriptionForUpdate(Long userId, LocalDateTime now);

    /**
     * Finds the subscription created by a given payment transaction.
     * Used to revoke access when KB later reports the transaction as
     * reversed / refunded / charged-back.
     */
    Optional<UserSubscription> findByTransactionId(String transactionId);

    void deleteByUserId(Long userId);

    @Query("SELECT COUNT(us) FROM UserSubscription us WHERE us.isActive = true AND us.endDate >= current_timestamp")
    long countActiveSubscriptions();

    @Query("SELECT us FROM UserSubscription us WHERE us.isActive = true AND us.endDate >= current_timestamp")
    List<UserSubscription> findAllActiveSubscriptions();
}
