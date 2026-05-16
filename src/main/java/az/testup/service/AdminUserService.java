package az.testup.service;

import az.testup.dto.response.AdminUserResponse;
import az.testup.entity.Exam;
import az.testup.entity.ExamPurchase;
import az.testup.entity.StudentSavedExam;
import az.testup.entity.User;
import az.testup.enums.AuditAction;
import az.testup.enums.Role;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.ExamPurchaseRepository;
import az.testup.repository.ExamRepository;
import az.testup.repository.StudentSavedExamRepository;
import az.testup.repository.UserRepository;
import az.testup.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final ExamRepository examRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final ExamPurchaseRepository examPurchaseRepository;
    private final StudentSavedExamRepository studentSavedExamRepository;
    private final AuditLogService auditLogService;

    public Page<AdminUserResponse> getUsers(String search, Role role, Pageable pageable) {
        return userRepository.searchUsers(
                (search != null && !search.isBlank()) ? search : null,
                role,
                pageable
        ).map(this::toResponse);
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
        AdminUserResponse response = toResponse(userRepository.save(user));
        auditLogService.log(AuditAction.USER_ROLE_CHANGED, "admin", "Admin", "USER", user.getEmail(), "Yeni rol: " + roleStr);
        return response;
    }

    @Transactional
    public AdminUserResponse toggleEnabled(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("İstifadəçi tapılmadı"));
        user.setEnabled(!user.isEnabled());
        AdminUserResponse response = toResponse(userRepository.save(user));
        auditLogService.log(AuditAction.USER_TOGGLED, "admin", "Admin", "USER", user.getEmail(),
                user.isEnabled() ? "Aktivləşdirildi" : "Deaktivləşdirildi");
        return response;
    }

    @Transactional
    public void deleteUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("İstifadəçi tapılmadı");
        }
        auditLogService.log(AuditAction.USER_DELETED, "admin", "Admin", "USER", userId.toString(), null);
        userSubscriptionRepository.deleteByUserId(userId);
        userRepository.deleteById(userId);
    }

    @Transactional
    public int bulkDelete(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return 0;
        int count = 0;
        for (Long id : userIds) {
            if (userRepository.existsById(id)) {
                userSubscriptionRepository.deleteByUserId(id);
                userRepository.deleteById(id);
                count++;
            }
        }
        auditLogService.logCurrent(AuditAction.USER_DELETED, "USER", "BULK",
                "Bulk delete: " + count + " istifadəçi silindi");
        return count;
    }

    @Transactional
    public int bulkToggleEnabled(List<Long> userIds, boolean enabled) {
        if (userIds == null || userIds.isEmpty()) return 0;
        int count = 0;
        for (Long id : userIds) {
            User u = userRepository.findById(id).orElse(null);
            if (u != null && u.isEnabled() != enabled) {
                u.setEnabled(enabled);
                userRepository.save(u);
                count++;
            }
        }
        auditLogService.logCurrent(AuditAction.USER_TOGGLED, "USER", "BULK",
                "Bulk " + (enabled ? "aktivləşdirildi" : "deaktiv edildi") + ": " + count + " istifadəçi");
        return count;
    }

    @Transactional
    public void assignExamToStudent(Long userId, Long examId) {
        User student = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("İstifadəçi tapılmadı"));
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (!examPurchaseRepository.existsByUserIdAndExamId(userId, examId)) {
            examPurchaseRepository.save(ExamPurchase.builder()
                    .user(student)
                    .exam(exam)
                    .amountPaid(BigDecimal.ZERO)
                    .build());
        }

        if (!studentSavedExamRepository.existsByStudentIdAndExamId(userId, examId)) {
            studentSavedExamRepository.save(StudentSavedExam.builder()
                    .student(student)
                    .exam(exam)
                    .build());
        }

        auditLogService.logCurrent(AuditAction.USER_EXAM_ASSIGNED, "USER", student.getEmail(),
                "İmtahan: " + exam.getTitle());
    }

    public AdminUserResponse toResponse(User user) {
        String activePlanName = userSubscriptionRepository
                .findActiveSubscriptionByUserIdAndDate(user.getId(), LocalDateTime.now())
                .map(sub -> sub.getPlan().getName())
                .orElse(null);

        return new AdminUserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getRole(),
                user.getProfilePicture(),
                user.getCreatedAt(),
                user.isEnabled(),
                activePlanName
        );
    }
}
