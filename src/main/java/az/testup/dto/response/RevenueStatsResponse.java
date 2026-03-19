package az.testup.dto.response;

import java.util.List;

public record RevenueStatsResponse(
        double totalRevenue,
        double thisMonthRevenue,
        double lastMonthRevenue,
        double growthPct,
        long totalPaidOrders,
        long activeSubscriptions,
        List<MonthlyItem> monthlyRevenue,
        List<PlanItem> revenueByPlan,
        List<RecentPayment> recentPayments
) {
    public record MonthlyItem(String month, double revenue, long orders) {}
    public record PlanItem(String planName, double revenue, long orders) {}
    public record RecentPayment(
            String orderId, String userEmail, String userName,
            String planName, double amount, long durationDays, String createdAt
    ) {}
}
