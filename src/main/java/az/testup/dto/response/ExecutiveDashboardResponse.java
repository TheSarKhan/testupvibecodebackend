package az.testup.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ExecutiveDashboardResponse(
        SummaryKpis summary,
        RevenueOverview revenue,
        SubscriptionOverview subscriptions,
        UserOverview users,
        ContentOverview content,
        OperationalAlerts operations,
        List<TopTeacher> topTeachers,
        List<TopExam> topExams,
        List<UpcomingExpiration> upcomingExpirations,
        List<RecentActivity> recentActivity
) {
    public record SummaryKpis(
            long totalUsers,
            long totalStudents,
            long totalTeachers,
            long totalAdmins,
            long totalExams,
            long totalSubmissions,
            long activeSubscriptions,
            double totalRevenue,
            double thisMonthRevenue,
            double lastMonthRevenue,
            double growthPct,
            double mrr,
            double arpu
    ) {}

    public record RevenueOverview(
            List<MonthlyPoint> monthly,
            List<PlanRevenue> byPlan,
            List<StatusCount> statusBreakdown,
            double yearToDate,
            long paidOrdersCount,
            long pendingOrdersCount,
            long failedOrdersCount
    ) {}

    public record SubscriptionOverview(
            long active,
            long expiringIn7Days,
            long expiringIn30Days,
            long cancelledThisMonth,
            List<PlanDistribution> distribution
    ) {}

    public record UserOverview(
            long totalUsers,
            long newThisMonth,
            long newLastMonth,
            double userGrowthPct,
            List<MonthlyPoint> monthlyRegistrations,
            Map<String, Long> byRole
    ) {}

    public record ContentOverview(
            long totalExams,
            long publishedExams,
            long draftExams,
            long bankQuestions,
            long templates,
            long banners,
            long tags
    ) {}

    public record OperationalAlerts(
            long pendingOrders,
            long unreadContactMessages,
            long pendingCollabApprovals,
            long systemErrors24h,
            long systemErrors7d,
            long untaggedExams
    ) {}

    public record TopTeacher(Long id, String name, String email, long examCount, long submissionCount) {}
    public record TopExam(Long id, String title, String teacherName, long submissionCount, String status) {}
    public record UpcomingExpiration(Long subscriptionId, String userName, String userEmail, String planName, LocalDateTime endDate, long daysLeft) {}
    public record RecentActivity(String action, String actorName, String targetType, String targetName, LocalDateTime createdAt) {}

    public record MonthlyPoint(String month, double value, long count) {}
    public record PlanRevenue(String planName, double revenue, long orders) {}
    public record StatusCount(String status, long count, double totalAmount) {}
    public record PlanDistribution(String planName, long activeCount, double share) {}
}
