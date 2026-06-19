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
import az.testup.repository.BankSubjectRepository;
import az.testup.repository.BankTopicRepository;
import az.testup.repository.ExamCollaboratorRepository;
import az.testup.repository.ExamPurchaseRepository;
import az.testup.repository.ExamRepository;
import az.testup.repository.NotificationRepository;
import az.testup.repository.PaymentOrderRepository;
import az.testup.repository.StudentSavedExamRepository;
import az.testup.repository.SubmissionRepository;
import az.testup.repository.TemplateRepository;
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
    private final SubmissionRepository submissionRepository;
    private final NotificationRepository notificationRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final ExamCollaboratorRepository examCollaboratorRepository;
    private final TemplateRepository templateRepository;
    private final BankSubjectRepository bankSubjectRepository;
    private final BankTopicRepository bankTopicRepository;
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
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("İstifadəçi tapılmadı"));
        String email = user.getEmail();
        removeOrAnonymize(user);
        auditLogService.log(AuditAction.USER_DELETED, "admin", "Admin", "USER", email, null);
    }

    @Transactional
    public int bulkDelete(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return 0;
        int count = 0;
        for (Long id : userIds) {
            User user = userRepository.findById(id).orElse(null);
            if (user == null) continue;
            removeOrAnonymize(user);
            count++;
        }
        auditLogService.logCurrent(AuditAction.USER_DELETED, "USER", "BULK",
                "Bulk delete: " + count + " istifadəçi silindi");
        return count;
    }

    /**
     * Deletes a user, choosing the right strategy by whether they AUTHORED content:
     *
     * - No authored content → hard delete. The row is removed after clearing the user's
     *   own consumer data (submissions, purchases, saved exams, notifications, payment
     *   orders, collaborator invitations, subscriptions). The previous version only
     *   cleared subscriptions, so any user who had ever submitted / bought / saved an
     *   exam failed with the opaque 409 "Verilənlər bazası xətası".
     *
     * - Authored content (exams, templates, question banks) → SOFT delete. We must keep
     *   the row, because hard-deleting it would force a cascade that wipes every student's
     *   submission (their result) on the teacher's exams. Instead the account is
     *   anonymized + disabled and flagged deleted, so it disappears from the admin list
     *   and can't log in, while the exams and all student results stay fully intact in the
     *   students' own accounts.
     */
    private void removeOrAnonymize(User user) {
        Long userId = user.getId();
        boolean authoredContent =
                examRepository.countByTeacherId(userId)
              + templateRepository.countByCreatedById(userId)
              + bankSubjectRepository.countByOwnerId(userId)
              + bankTopicRepository.countByOwnerId(userId) > 0;

        if (authoredContent) {
            user.setDeleted(true);
            user.setEnabled(false);
            user.setFullName("Silinmiş istifadəçi");
            // Free the original email (so the person can re-register) and guarantee the
            // unique constraint can't collide; userId makes it unique.
            user.setEmail("deleted_" + userId + "@silinmis.local");
            user.setGoogleSub(null);
            user.setPassword(null);
            user.setPhoneNumber(null);
            user.setProfilePicture(null);
            userRepository.save(user);
            return;
        }

        purgeConsumerData(userId);
        userRepository.delete(user);
    }

    /**
     * Clears a user's own consumer-side data so their row can be hard-deleted without
     * tripping a foreign-key constraint. Uses load + deleteAll (not a bulk JPQL delete)
     * so JPA cascades fire — e.g. Submission → answers (orphanRemoval), which a bulk
     * delete would orphan.
     */
    private void purgeConsumerData(Long userId) {
        submissionRepository.deleteAll(submissionRepository.findByStudentId(userId));
        examPurchaseRepository.deleteAll(examPurchaseRepository.findByUserId(userId));
        studentSavedExamRepository.deleteAll(studentSavedExamRepository.findByStudentIdOrderBySavedAtDesc(userId));
        notificationRepository.deleteAll(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId));
        paymentOrderRepository.deleteAll(paymentOrderRepository.findByUserId(userId));
        examCollaboratorRepository.deleteAll(examCollaboratorRepository.findByTeacherId(userId));
        userSubscriptionRepository.deleteByUserId(userId);
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
