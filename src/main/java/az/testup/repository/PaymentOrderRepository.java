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
     * Atomically marks an order PAID, but only if it isn't already PAID.
     * Returns 1 if THIS call won the race (caller must run activation logic),
     * 0 if a concurrent request already activated. Lets verify-and-callback
     * race safely from either side without double-charging subscriptions.
     */
    @Modifying
    @Query("UPDATE PaymentOrder o SET o.status = 'PAID' WHERE o.orderId = :orderId AND o.status <> 'PAID'")
    int markAsPaid(@Param("orderId") String orderId);

    /**
     * Finds orders stuck in PENDING or PROCESSING for longer than the cutoff,
     * so the scheduler can recover abandoned payments.
     */
    @Query("SELECT o FROM PaymentOrder o WHERE o.status IN ('PENDING', 'PROCESSING') AND o.createdAt < :cutoff")
    List<PaymentOrder> findStuckOrders(@Param("cutoff") LocalDateTime cutoff);

    long countByUserIdAndExamIdAndStatus(Long userId, Long examId, String status);

    // All of a user's payment orders — used to purge their data on account deletion.
    List<PaymentOrder> findByUserId(Long userId);

    @Query("SELECT o FROM PaymentOrder o JOIN FETCH o.exam e WHERE o.user.id = :userId AND o.exam IS NOT NULL AND o.status = :status AND e.deleted = false")
    List<PaymentOrder> findPaidExamOrders(@Param("userId") Long userId, @Param("status") String status);

    long countByStatus(String status);

    List<PaymentOrder> findTop10ByStatusOrderByCreatedAtDesc(String status);

    List<PaymentOrder> findByStatusOrderByCreatedAtDesc(String status);

    org.springframework.data.domain.Page<PaymentOrder> findByStatusOrderByCreatedAtDesc(
            String status, org.springframework.data.domain.Pageable pageable);

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

    @Query(value = "SELECT status, COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS total FROM payment_orders GROUP BY status", nativeQuery = true)
    List<Object[]> statusBreakdown();

    @Query("SELECT o FROM PaymentOrder o WHERE o.status = :status " +
            "AND (:from IS NULL OR o.createdAt >= :from) " +
            "AND (:to IS NULL OR o.createdAt < :to) " +
            "ORDER BY o.createdAt DESC")
    List<PaymentOrder> findForExport(@Param("status") String status,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);
}
