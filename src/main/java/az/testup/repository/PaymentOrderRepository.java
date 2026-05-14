package az.testup.repository;

import az.testup.entity.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {
    Optional<PaymentOrder> findByOrderId(String orderId);

    /**
     * Atomically transitions an order from PENDING to PROCESSING.
     * Returns 1 if the update succeeded (this thread "won"), 0 if another thread
     * already claimed the order. Prevents double-activation on concurrent verify calls.
     */
    @Modifying
    @Query("UPDATE PaymentOrder o SET o.status = 'PROCESSING' WHERE o.orderId = :orderId AND o.status = 'PENDING'")
    int claimForProcessing(@Param("orderId") String orderId);

    /**
     * Reclaims a stale PROCESSING order (one whose processor crashed mid-activation).
     * Used by the scheduler to recover orders stuck in PROCESSING for too long.
     * Without this, claimForProcessing would always return 0 for PROCESSING rows
     * and the scheduler would skip them forever — losing the user's payment.
     */
    @Modifying
    @Query("UPDATE PaymentOrder o SET o.status = 'PROCESSING' WHERE o.orderId = :orderId AND o.status IN ('PENDING', 'PROCESSING') AND o.createdAt < :cutoff")
    int reclaimStaleOrder(@Param("orderId") String orderId, @Param("cutoff") LocalDateTime cutoff);

    /**
     * Atomically marks an order as PAID only if it is currently in a state
     * that can legitimately transition to PAID (i.e. PENDING or PROCESSING).
     * Returns 1 on successful activation, 0 otherwise.
     *
     * Restricting to PENDING/PROCESSING prevents two real problems:
     *   - Double-activation: an already-PAID order won't be re-activated,
     *     which would otherwise extend the user's subscription twice for one payment.
     *   - Resurrection: a FAILED or CANCELLED order (admin-cancelled, or KB-failed)
     *     cannot be revived by a late-arriving KB callback claiming Paid.
     */
    @Modifying
    @Query("UPDATE PaymentOrder o SET o.status = 'PAID' WHERE o.orderId = :orderId AND o.status IN ('PENDING', 'PROCESSING')")
    int markAsPaidIfNotAlready(@Param("orderId") String orderId);

    /**
     * Finds orders stuck in PENDING or PROCESSING for longer than the cutoff,
     * so the scheduler can recover abandoned payments.
     */
    @Query("SELECT o FROM PaymentOrder o WHERE o.status IN ('PENDING', 'PROCESSING') AND o.createdAt < :cutoff")
    List<PaymentOrder> findStuckOrders(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Finds a recent PENDING order for the same user+plan combo. Used to short-circuit
     * repeated /initiate calls so the user (or a misbehaving frontend) cannot generate
     * a flood of KB orders by clicking "Pay" many times in succession.
     */
    @Query("SELECT o FROM PaymentOrder o WHERE o.user.id = :userId AND o.plan.id = :planId "
            + "AND o.status = 'PENDING' AND o.createdAt > :since ORDER BY o.createdAt DESC")
    List<PaymentOrder> findRecentPendingForUserAndPlan(
            @Param("userId") Long userId,
            @Param("planId") Long planId,
            @Param("since") LocalDateTime since);

    /** Same as above but for exam purchases. */
    @Query("SELECT o FROM PaymentOrder o WHERE o.user.id = :userId AND o.exam.id = :examId "
            + "AND o.status = 'PENDING' AND o.createdAt > :since ORDER BY o.createdAt DESC")
    List<PaymentOrder> findRecentPendingForUserAndExam(
            @Param("userId") Long userId,
            @Param("examId") Long examId,
            @Param("since") LocalDateTime since);

    long countByUserIdAndExamIdAndStatus(Long userId, Long examId, String status);

    @Query("SELECT o FROM PaymentOrder o JOIN FETCH o.exam e WHERE o.user.id = :userId AND o.exam IS NOT NULL AND o.status = :status AND e.deleted = false")
    List<PaymentOrder> findPaidExamOrders(@Param("userId") Long userId, @Param("status") String status);

    long countByStatus(String status);

    List<PaymentOrder> findTop10ByStatusOrderByCreatedAtDesc(String status);

    List<PaymentOrder> findByStatusOrderByCreatedAtDesc(String status);

    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM payment_orders WHERE status = 'PAID'", nativeQuery = true)
    Double totalRevenue();

    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM payment_orders WHERE status = 'PAID' AND DATE_TRUNC('month', created_at) = DATE_TRUNC('month', CURRENT_DATE)", nativeQuery = true)
    Double thisMonthRevenue();

    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM payment_orders WHERE status = 'PAID' AND DATE_TRUNC('month', created_at) = DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month')", nativeQuery = true)
    Double lastMonthRevenue();

    @Query(value = "SELECT TO_CHAR(created_at, 'YYYY-MM') AS month, COALESCE(SUM(amount), 0) AS revenue, COUNT(*) AS orders FROM payment_orders WHERE status = 'PAID' GROUP BY TO_CHAR(created_at, 'YYYY-MM') ORDER BY month DESC LIMIT 6", nativeQuery = true)
    List<Object[]> monthlyRevenue();

    @Query(value = "SELECT sp.name AS plan_name, COALESCE(SUM(po.amount), 0) AS revenue, COUNT(po.id) AS orders FROM payment_orders po JOIN subscription_plans sp ON po.plan_id = sp.id WHERE po.status = 'PAID' GROUP BY sp.name ORDER BY revenue DESC", nativeQuery = true)
    List<Object[]> revenueByPlan();
}
