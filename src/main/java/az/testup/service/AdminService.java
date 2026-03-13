package az.testup.service;

import az.testup.dto.response.AdminExamResponse;
import az.testup.dto.response.AdminStatsResponse;
import az.testup.dto.response.AdminUserResponse;
import az.testup.entity.Exam;
import az.testup.entity.ExamSubject;
import az.testup.entity.User;
import az.testup.enums.ExamStatus;
import az.testup.enums.Role;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.ExamRepository;
import az.testup.repository.ExamSubjectRepository;
import az.testup.repository.SubmissionRepository;
import az.testup.repository.UserRepository;
import az.testup.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import az.testup.dto.response.MonthlyStatPoint;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final ExamRepository examRepository;
    private final SubmissionRepository submissionRepository;
    private final ExamSubjectRepository subjectRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;


    public AdminStatsResponse getStats() {
        long totalUsers = userRepository.count();
        long totalTeachers = userRepository.countByRole(Role.TEACHER);
        long totalStudents = userRepository.countByRole(Role.STUDENT);
        long totalExams = examRepository.count();
        long totalSubmissions = submissionRepository.count();
        var recentUsers = userRepository.findTop5ByOrderByCreatedAtDesc()
                .stream().map(this::mapToResponse).toList();

        LocalDateTime since = LocalDateTime.now().minusMonths(5).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<MonthlyStatPoint> monthlyRegistrations = toMonthlyPoints(userRepository.countRegistrationsByMonth(since));
        List<MonthlyStatPoint> monthlySubmissions = toMonthlyPoints(submissionRepository.countSubmissionsByMonth(since));

        return new AdminStatsResponse(totalUsers, totalTeachers, totalStudents,
                totalExams, totalSubmissions, recentUsers, monthlyRegistrations, monthlySubmissions);
    }

    private List<MonthlyStatPoint> toMonthlyPoints(List<Object[]> rows) {
        return rows.stream()
                .map(r -> new MonthlyStatPoint((String) r[0], ((Number) r[1]).longValue()))
                .collect(Collectors.toList());
    }

    public Page<AdminUserResponse> getUsers(String search, Role role, Pageable pageable) {
        return userRepository.searchUsers(
                (search != null && !search.isBlank()) ? search : null,
                role,
                pageable
        ).map(this::mapToResponse);
    }

    @Transactional
    public AdminUserResponse changeRole(Long userId, String roleStr) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("İstifadəçi tapılmadı"));
        try {
            user.setRole(Role.valueOf(roleStr));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Yanlış rol: " + roleStr);
        }
        return mapToResponse(userRepository.save(user));
    }

    @Transactional
    public AdminUserResponse toggleEnabled(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("İstifadəçi tapılmadı"));
        user.setEnabled(!user.isEnabled());
        return mapToResponse(userRepository.save(user));
    }

    @Transactional
    public void deleteUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("İstifadəçi tapılmadı");
        }
        userRepository.deleteById(userId);
    }

    // ───────── Exam management ─────────

    public Page<AdminExamResponse> getExams(String search, ExamStatus status, Long teacherId, String teacherRoleName, Pageable pageable) {
        return examRepository.searchExams(
                (search != null && !search.isBlank()) ? search : null,
                status,
                teacherId,
                teacherRoleName,
                pageable
        ).map(this::mapToExamResponse);
    }

    @Transactional
    public AdminExamResponse toggleSitePublished(Long examId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        exam.setSitePublished(!exam.isSitePublished());
        return mapToExamResponse(examRepository.save(exam));
    }

    @Transactional
    public AdminExamResponse setExamPrice(Long examId, BigDecimal price) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        exam.setPrice(price);
        return mapToExamResponse(examRepository.save(exam));
    }

    @Transactional
    public void deleteExam(Long examId) {
        if (!examRepository.existsById(examId)) {
            throw new ResourceNotFoundException("İmtahan tapılmadı");
        }
        examRepository.deleteById(examId);
    }

    // ───────── Subject management ─────────

    public List<ExamSubject> getSubjects() {
        return subjectRepository.findAllByOrderByNameAsc();
    }

    @Transactional
    public ExamSubject addSubject(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) throw new BadRequestException("Fənn adı boş ola bilməz");
        if (subjectRepository.existsByName(trimmed)) {
            throw new BadRequestException("Bu fənn artıq mövcuddur");
        }
        return subjectRepository.save(ExamSubject.builder()
                .name(trimmed)
                .isDefault(false)
                .build());
    }

    @Transactional
    public void deleteSubject(Long id) {
        ExamSubject subject = subjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (subject.isDefault()) {
            throw new BadRequestException("Default fənnlər silinə bilməz");
        }
        subjectRepository.deleteById(id);
    }

    // ───────── Mappers ─────────

    private AdminExamResponse mapToExamResponse(Exam exam) {
        return new AdminExamResponse(
                exam.getId(),
                exam.getTitle(),
                exam.getTeacher().getFullName(),
                exam.getTeacher().getEmail(),
                exam.getSubjects(),
                exam.getStatus(),
                exam.isSitePublished(),
                exam.getPrice(),
                exam.getQuestions().size(),
                exam.getShareLink(),
                exam.getCreatedAt()
        );
    }

    private AdminUserResponse mapToResponse(User user) {
        String activePlanName = userSubscriptionRepository.findActiveSubscriptionByUserIdAndDate(user.getId(), LocalDateTime.now())
                .map(sub -> sub.getPlan().getName())
                .orElse(null);

        return new AdminUserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getProfilePicture(),
                user.getCreatedAt(),
                user.isEnabled(),
                activePlanName
        );
    }

}
