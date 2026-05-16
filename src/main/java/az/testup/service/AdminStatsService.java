package az.testup.service;

import az.testup.dto.response.AdminStatsResponse;
import az.testup.dto.response.MonthlyStatPoint;
import az.testup.enums.ExamStatus;
import az.testup.enums.Role;
import az.testup.repository.ExamRepository;
import az.testup.repository.SubmissionRepository;
import az.testup.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private final UserRepository userRepository;
    private final ExamRepository examRepository;
    private final SubmissionRepository submissionRepository;
    private final AdminUserService adminUserService;
    private final AdminExamService adminExamService;

    public AdminStatsResponse getStats() {
        long totalUsers = userRepository.count();
        long totalTeachers = userRepository.countByRole(Role.TEACHER);
        long totalStudents = userRepository.countByRole(Role.STUDENT);
        long totalExams = examRepository.count();
        long totalPublishedExams = examRepository.countByStatus(ExamStatus.PUBLISHED)
                + examRepository.countByStatus(ExamStatus.ACTIVE);
        long totalSubmissions = submissionRepository.count();

        var recentUsers = userRepository.findTop5ByOrderByCreatedAtDesc()
                .stream().map(adminUserService::toResponse).toList();
        var recentExams = examRepository.findTop5ByDeletedFalseOrderByCreatedAtDesc()
                .stream().map(adminExamService::toResponse).toList();

        LocalDateTime since = LocalDateTime.now().minusMonths(5)
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<MonthlyStatPoint> monthlyRegistrations = toMonthlyPoints(
                userRepository.countRegistrationsByMonth(since));
        List<MonthlyStatPoint> monthlySubmissions = toMonthlyPoints(
                submissionRepository.countSubmissionsByMonth(since));

        return new AdminStatsResponse(totalUsers, totalTeachers, totalStudents,
                totalExams, totalPublishedExams, totalSubmissions, recentUsers, recentExams,
                monthlyRegistrations, monthlySubmissions);
    }

    private List<MonthlyStatPoint> toMonthlyPoints(List<Object[]> rows) {
        return rows.stream()
                .map(r -> new MonthlyStatPoint((String) r[0], ((Number) r[1]).longValue()))
                .collect(Collectors.toList());
    }
}
