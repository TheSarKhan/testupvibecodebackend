package az.testup.repository;

import az.testup.entity.PayriffOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PayriffOrderRepository extends JpaRepository<PayriffOrder, Long> {
    Optional<PayriffOrder> findByOrderId(String orderId);

    /**
     * Atomically transitions an order from PENDING to PROCESSING.
     * Returns 1 if the update succeeded (this thread "won"), 0 if another thread
     * already claimed the order. Prevents double-activation on concurrent verify calls.
     */
    @Modifying
    @Query("UPDATE PayriffOrder o SET o.status = 'PROCESSING' WHERE o.orderId = :orderId AND o.status = 'PENDING'")
    int claimForProcessing(@Param("orderId") String orderId);

    /**
     * Finds orders that have been stuck in PENDING or PROCESSING for longer than the
     * given cutoff time, so the scheduler can recover abandoned payments.
     */
    @Query("SELECT o FROM PayriffOrder o WHERE o.status IN ('PENDING', 'PROCESSING') AND o.createdAt < :cutoff")
    List<PayriffOrder> findStuckOrders(@Param("cutoff") LocalDateTime cutoff);
    long countByUserIdAndExamIdAndStatus(Long userId, Long examId, String status);

    @Query("SELECT o FROM PayriffOrder o JOIN FETCH o.exam e WHERE o.user.id = :userId AND o.exam IS NOT NULL AND o.status = :status AND e.deleted = false")
    List<PayriffOrder> findPaidExamOrders(@Param("userId") Long userId, @Param("status") String status);

    long countByStatus(String status);

    List<PayriffOrder> findTop10ByStatusOrderByCreatedAtDesc(String status);

    List<PayriffOrder> findByStatusOrderByCreatedAtDesc(String status);

    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM payriff_orders WHERE status = 'PAID'", nativeQuery = true)
    Double totalRevenue();

    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM payriff_orders WHERE status = 'PAID' AND DATE_TRUNC('month', created_at) = DATE_TRUNC('month', CURRENT_DATE)", nativeQuery = true)
    Double thisMonthRevenue();

    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM payriff_orders WHERE status = 'PAID' AND DATE_TRUNC('month', created_at) = DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month')", nativeQuery = true)
    Double lastMonthRevenue();

    @Query(value = "SELECT TO_CHAR(created_at, 'YYYY-MM') AS month, COALESCE(SUM(amount), 0) AS revenue, COUNT(*) AS orders FROM payriff_orders WHERE status = 'PAID' GROUP BY TO_CHAR(created_at, 'YYYY-MM') ORDER BY month DESC LIMIT 6", nativeQuery = true)
    List<Object[]> monthlyRevenue();

    @Query(value = "SELECT sp.name AS plan_name, COALESCE(SUM(po.amount), 0) AS revenue, COUNT(po.id) AS orders FROM payriff_orders po JOIN subscription_plans sp ON po.plan_id = sp.id WHERE po.status = 'PAID' GROUP BY sp.name ORDER BY revenue DESC", nativeQuery = true)
    List<Object[]> revenueByPlan();
}
