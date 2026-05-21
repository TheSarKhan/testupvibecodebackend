package az.testup.service;

import az.testup.dto.response.ExecutiveDashboardResponse;
import az.testup.dto.response.ExecutiveDashboardResponse.*;
import az.testup.entity.AuditLog;
import az.testup.entity.UserSubscription;
import az.testup.enums.ExamStatus;
import az.testup.enums.Role;
import az.testup.repository.AuditLogRepository;
import az.testup.repository.BankQuestionRepository;
import az.testup.repository.BannerRepository;
import az.testup.repository.ContactMessageRepository;
import az.testup.repository.ExamRepository;
import az.testup.repository.PaymentOrderRepository;
import az.testup.repository.SubmissionRepository;
import az.testup.repository.TagRepository;
import az.testup.repository.TemplateRepository;
import az.testup.repository.UserRepository;
import az.testup.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final UserRepository userRepository;
    private final ExamRepository examRepository;
    private final SubmissionRepository submissionRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final UserSubscriptionRepository subscriptionRepository;
    private final ContactMessageRepository contactRepository;
    private final AuditLogRepository auditLogRepository;
    private final BankQuestionRepository bankQuestionRepository;
    private final BannerRepository bannerRepository;
    private final TagRepository tagRepository;
    private final TemplateRepository templateRepository;

    @Transactional(readOnly = true)
    public ExecutiveDashboardResponse getExecutiveOverview() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfMonth = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
        LocalDateTime startOfLastMonth = startOfMonth.minusMonths(1);
        LocalDateTime startOfYear = LocalDate.of(now.getYear(), 1, 1).atStartOfDay();
        LocalDateTime last24h = now.minusDays(1);
        LocalDateTime last7d = now.minusDays(7);

        return new ExecutiveDashboardResponse(
                buildSummary(),
                buildRevenue(startOfYear),
                buildSubscriptions(now, startOfMonth),
                buildUsers(now, startOfMonth, startOfLastMonth),
                buildContent(),
                buildOperations(last24h, last7d),
                buildTopTeachers(),
                buildTopExams(),
                buildUpcomingExpirations(now, now.plusDays(30)),
                buildRecentActivity()
        );
    }

    private SummaryKpis buildSummary() {
        long totalUsers = userRepository.count();
        long students = userRepository.countByRole(Role.STUDENT);
        long teachers = userRepository.countByRole(Role.TEACHER);
        long admins = Math.max(0, totalUsers - students - teachers);
        long totalExams = examRepository.count();
        long totalSubmissions = submissionRepository.count();
        long activeSubs = subscriptionRepository.countActiveSubscriptions();

        double total = nz(paymentOrderRepository.totalRevenue());
        double thisMonth = nz(paymentOrderRepository.thisMonthRevenue());
        double lastMonth = nz(paymentOrderRepository.lastMonthRevenue());
        double growth = lastMonth > 0 ? ((thisMonth - lastMonth) / lastMonth) * 100.0 : (thisMonth > 0 ? 100.0 : 0.0);

        // MRR estimate: this month's revenue
        double mrr = thisMonth;
        double arpu = activeSubs > 0 ? mrr / activeSubs : 0.0;

        return new SummaryKpis(
                totalUsers, students, teachers, admins,
                totalExams, totalSubmissions, activeSubs,
                round(total), round(thisMonth), round(lastMonth),
                round(growth), round(mrr), round(arpu)
        );
    }

    private RevenueOverview buildRevenue(LocalDateTime startOfYear) {
        List<MonthlyPoint> monthly = new ArrayList<>();
        for (Object[] row : paymentOrderRepository.monthlyRevenue()) {
            monthly.add(new MonthlyPoint(
                    row[0].toString(),
                    ((Number) row[1]).doubleValue(),
                    ((Number) row[2]).longValue()
            ));
        }
        Collections.reverse(monthly);

        List<PlanRevenue> byPlan = new ArrayList<>();
        for (Object[] row : paymentOrderRepository.revenueByPlan()) {
            byPlan.add(new PlanRevenue(
                    row[0].toString(),
                    ((Number) row[1]).doubleValue(),
                    ((Number) row[2]).longValue()
            ));
        }

        List<StatusCount> statusBreakdown = new ArrayList<>();
        long pendingOrders = 0, paidOrders = 0, failedOrders = 0;
        for (Object[] row : paymentOrderRepository.statusBreakdown()) {
            String status = (String) row[0];
            long count = ((Number) row[1]).longValue();
            double amount = ((Number) row[2]).doubleValue();
            statusBreakdown.add(new StatusCount(status, count, amount));
            if ("PENDING".equals(status)) pendingOrders = count;
            else if ("PAID".equals(status)) paidOrders = count;
            else if ("FAILED".equals(status)) failedOrders = count;
        }

        // YTD revenue: sum of monthly within current year
        double ytd = monthly.stream()
                .filter(m -> m.month().startsWith(String.valueOf(LocalDate.now().getYear())))
                .mapToDouble(MonthlyPoint::value)
                .sum();

        return new RevenueOverview(monthly, byPlan, statusBreakdown,
                round(ytd), paidOrders, pendingOrders, failedOrders);
    }

    private SubscriptionOverview buildSubscriptions(LocalDateTime now, LocalDateTime startOfMonth) {
        long active = subscriptionRepository.countActiveSubscriptions();
        long exp7 = subscriptionRepository.countActiveExpiringBetween(now, now.plusDays(7));
        long exp30 = subscriptionRepository.countActiveExpiringBetween(now, now.plusDays(30));
        long cancelled = subscriptionRepository.countCancelledSince(startOfMonth);

        List<PlanDistribution> dist = new ArrayList<>();
        long total = 0;
        for (Object[] row : subscriptionRepository.countActiveByPlan()) {
            total += ((Number) row[1]).longValue();
        }
        for (Object[] row : subscriptionRepository.countActiveByPlan()) {
            String plan = (String) row[0];
            long cnt = ((Number) row[1]).longValue();
            double share = total > 0 ? (cnt * 100.0 / total) : 0.0;
            dist.add(new PlanDistribution(plan, cnt, round(share)));
        }

        return new SubscriptionOverview(active, exp7, exp30, cancelled, dist);
    }

    private UserOverview buildUsers(LocalDateTime now, LocalDateTime startOfMonth, LocalDateTime startOfLastMonth) {
        long total = userRepository.count();

        // Monthly registrations from past 6 months
        LocalDateTime since = now.minusMonths(5).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<MonthlyPoint> monthly = new ArrayList<>();
        long newThisMonth = 0, newLastMonth = 0;
        String currentMonthStr = String.format("%04d-%02d", now.getYear(), now.getMonthValue());
        String lastMonthStr = String.format("%04d-%02d", startOfLastMonth.getYear(), startOfLastMonth.getMonthValue());
        for (Object[] row : userRepository.countRegistrationsByMonth(since)) {
            String month = (String) row[0];
            long count = ((Number) row[1]).longValue();
            monthly.add(new MonthlyPoint(month, count, count));
            if (month.equals(currentMonthStr)) newThisMonth = count;
            if (month.equals(lastMonthStr)) newLastMonth = count;
        }

        double growth = newLastMonth > 0 ? ((newThisMonth - newLastMonth) * 100.0 / newLastMonth) : (newThisMonth > 0 ? 100.0 : 0.0);

        Map<String, Long> byRole = new LinkedHashMap<>();
        byRole.put("STUDENT", userRepository.countByRole(Role.STUDENT));
        byRole.put("TEACHER", userRepository.countByRole(Role.TEACHER));
        byRole.put("ADMIN", userRepository.countByRole(Role.ADMIN));

        return new UserOverview(total, newThisMonth, newLastMonth, round(growth), monthly, byRole);
    }

    private ContentOverview buildContent() {
        long totalExams = examRepository.count();
        long published = examRepository.countByStatus(ExamStatus.PUBLISHED) + examRepository.countByStatus(ExamStatus.ACTIVE);
        long drafts = examRepository.countByStatus(ExamStatus.DRAFT);
        long bank = bankQuestionRepository.count();
        long templates = templateRepository.count();
        long banners = bannerRepository.count();
        long tags = tagRepository.count();
        return new ContentOverview(totalExams, published, drafts, bank, templates, banners, tags);
    }

    private OperationalAlerts buildOperations(LocalDateTime last24h, LocalDateTime last7d) {
        long pendingOrders = paymentOrderRepository.countByStatus("PENDING");
        long unread = safe(() -> contactRepository.countByIsReadFalse());
        long pendingCollab = 0;
        try {
            // Reflection-free direct: use auditlog or just 0 since interface unknown
            // We'll let frontend compute from collaborative-exams pending-count endpoint already
        } catch (Exception ignored) {}
        long errors24h = auditLogRepository.countSystemErrorsSince(last24h);
        long errors7d = auditLogRepository.countSystemErrorsSince(last7d);
        long untagged = examRepository.countUntaggedExams();

        return new OperationalAlerts(pendingOrders, unread, pendingCollab, errors24h, errors7d, untagged);
    }

    private List<TopTeacher> buildTopTeachers() {
        List<TopTeacher> result = new ArrayList<>();
        for (Object[] row : examRepository.findTopTeachersByExamCount(5)) {
            Long teacherId = ((Number) row[0]).longValue();
            long examCount = ((Number) row[1]).longValue();
            long subCount = ((Number) row[2]).longValue();
            userRepository.findById(teacherId).ifPresent(u ->
                result.add(new TopTeacher(u.getId(), u.getFullName(), u.getEmail(), examCount, subCount))
            );
        }
        return result;
    }

    private List<TopExam> buildTopExams() {
        List<TopExam> result = new ArrayList<>();
        for (Object[] row : examRepository.findTopExamsBySubmissions(5)) {
            result.add(new TopExam(
                    ((Number) row[0]).longValue(),
                    (String) row[1],
                    (String) row[3],
                    ((Number) row[4]).longValue(),
                    row[2] != null ? row[2].toString() : null
            ));
        }
        return result;
    }

    private List<UpcomingExpiration> buildUpcomingExpirations(LocalDateTime from, LocalDateTime to) {
        List<UpcomingExpiration> result = new ArrayList<>();
        List<UserSubscription> expiring = subscriptionRepository.findExpiringBetween(from, to);
        for (UserSubscription us : expiring.stream().limit(10).toList()) {
            long days = java.time.Duration.between(from, us.getEndDate()).toDays();
            result.add(new UpcomingExpiration(
                    us.getId(),
                    us.getUser() != null ? us.getUser().getFullName() : "?",
                    us.getUser() != null ? us.getUser().getEmail() : "?",
                    us.getPlan() != null ? us.getPlan().getName() : "?",
                    us.getEndDate(),
                    days
            ));
        }
        return result;
    }

    private List<RecentActivity> buildRecentActivity() {
        List<RecentActivity> result = new ArrayList<>();
        List<AuditLog> recent = auditLogRepository.findRecentActivity(PageRequest.of(0, 10));
        for (AuditLog log : recent) {
            result.add(new RecentActivity(
                    log.getAction().name(),
                    log.getActorName() != null ? log.getActorName() : log.getActorEmail(),
                    log.getTargetType(),
                    log.getTargetName(),
                    log.getCreatedAt()
            ));
        }
        return result;
    }

    private static double nz(Double v) { return v != null ? v : 0.0; }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
    private static long safe(java.util.function.LongSupplier sup) {
        try { return sup.getAsLong(); } catch (Exception e) { return 0; }
    }

    @SuppressWarnings("unused")
    private static <T> Optional<T> opt(java.util.function.Supplier<T> sup) {
        try { return Optional.ofNullable(sup.get()); } catch (Exception e) { return Optional.empty(); }
    }
}
