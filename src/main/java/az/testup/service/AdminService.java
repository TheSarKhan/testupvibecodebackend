package az.testup.service;

import az.testup.dto.response.AdminExamResponse;
import az.testup.dto.response.AdminStatsResponse;
import az.testup.dto.response.AdminUserResponse;
import az.testup.entity.Exam;
import az.testup.entity.User;
import az.testup.enums.ExamStatus;
import az.testup.enums.Role;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.ExamRepository;
import az.testup.repository.SubmissionRepository;
import az.testup.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final ExamRepository examRepository;
    private final SubmissionRepository submissionRepository;

    public AdminStatsResponse getStats() {
        long totalUsers = userRepository.count();
        long totalTeachers = userRepository.countByRole(Role.TEACHER);
        long totalStudents = userRepository.countByRole(Role.STUDENT);
        long totalExams = examRepository.count();
        long totalSubmissions = submissionRepository.count();
        var recentUsers = userRepository.findTop5ByOrderByCreatedAtDesc()
                .stream().map(this::mapToResponse).toList();

        return new AdminStatsResponse(totalUsers, totalTeachers, totalStudents,
                totalExams, totalSubmissions, recentUsers);
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

    private AdminExamResponse mapToExamResponse(Exam exam) {
        return new AdminExamResponse(
                exam.getId(),
                exam.getTitle(),
                exam.getTeacher().getFullName(),
                exam.getTeacher().getEmail(),
                exam.getSubject(),
                exam.getStatus(),
                exam.isSitePublished(),
                exam.getPrice(),
                exam.getQuestions().size(),
                exam.getShareLink(),
                exam.getCreatedAt()
        );
    }

    // ───────── User management ─────────

    private AdminUserResponse mapToResponse(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getProfilePicture(),
                user.getCreatedAt()
        );
    }
}
