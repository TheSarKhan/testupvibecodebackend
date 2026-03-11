package az.testup.dto.response;

import java.util.List;

public record AdminStatsResponse(
        long totalUsers,
        long totalTeachers,
        long totalStudents,
        long totalExams,
        long totalSubmissions,
        List<AdminUserResponse> recentUsers
) {}
