package az.testup.dto.response;

import java.util.List;

public record AdminStatsResponse(
        long totalUsers,
        long totalTeachers,
        long totalStudents,
        long totalExams,
        long totalPublishedExams,
        long totalSubmissions,
        List<AdminUserResponse> recentUsers,
        List<AdminExamResponse> recentExams,
        List<MonthlyStatPoint> monthlyRegistrations,
        List<MonthlyStatPoint> monthlySubmissions
) {}
