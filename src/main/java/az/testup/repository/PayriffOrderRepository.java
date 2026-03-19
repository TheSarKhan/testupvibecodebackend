package az.testup.repository;

import az.testup.entity.PayriffOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PayriffOrderRepository extends JpaRepository<PayriffOrder, Long> {
    Optional<PayriffOrder> findByOrderId(String orderId);

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
