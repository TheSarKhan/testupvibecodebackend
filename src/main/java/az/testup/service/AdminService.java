package az.testup.service;

import az.testup.dto.request.AdminNotificationRequest;
import az.testup.dto.response.AdminExamResponse;
import az.testup.dto.response.AdminStatsResponse;
import az.testup.dto.response.AdminUserResponse;
import az.testup.dto.response.NotificationLogResponse;
import az.testup.dto.response.SubjectStatsResponse;
import az.testup.entity.Exam;
import az.testup.entity.ExamSubject;
import az.testup.entity.NotificationLog;
import az.testup.entity.SubjectTopic;
import az.testup.entity.User;
import az.testup.enums.AuditAction;
import az.testup.enums.Difficulty;
import az.testup.enums.ExamStatus;
import az.testup.enums.Role;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.entity.ExamPurchase;
import az.testup.entity.StudentSavedExam;
import az.testup.repository.BankQuestionRepository;
import az.testup.repository.ExamPurchaseRepository;
import az.testup.repository.ExamRepository;
import az.testup.repository.ExamSubjectRepository;
import az.testup.repository.NotificationLogRepository;
import az.testup.repository.SubjectTopicRepository;
import az.testup.repository.SubmissionRepository;
import az.testup.repository.StudentSavedExamRepository;
import az.testup.repository.UserRepository;
import az.testup.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import az.testup.dto.response.MonthlyStatPoint;
import az.testup.enums.NotificationType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final ExamRepository examRepository;
    private final SubmissionRepository submissionRepository;
    private final ExamSubjectRepository subjectRepository;
    private final SubjectTopicRepository subjectTopicRepository;
    private final BankQuestionRepository bankQuestionRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final NotificationLogRepository notificationLogRepository;
    private final ExamPurchaseRepository examPurchaseRepository;
    private final StudentSavedExamRepository studentSavedExamRepository;
    private final AuditLogService auditLogService;


    public AdminStatsResponse getStats() {
        long totalUsers = userRepository.count();
        long totalTeachers = userRepository.countByRole(Role.TEACHER);
        long totalStudents = userRepository.countByRole(Role.STUDENT);
        long totalExams = examRepository.count();
        long totalPublishedExams = examRepository.countByStatus(ExamStatus.PUBLISHED)
                + examRepository.countByStatus(ExamStatus.ACTIVE);
        long totalSubmissions = submissionRepository.count();
        var recentUsers = userRepository.findTop5ByOrderByCreatedAtDesc()
                .stream().map(this::mapToResponse).toList();
        var recentExams = examRepository.findTop5ByDeletedFalseOrderByCreatedAtDesc()
                .stream().map(this::mapToExamResponse).toList();

        LocalDateTime since = LocalDateTime.now().minusMonths(5).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<MonthlyStatPoint> monthlyRegistrations = toMonthlyPoints(userRepository.countRegistrationsByMonth(since));
        List<MonthlyStatPoint> monthlySubmissions = toMonthlyPoints(submissionRepository.countSubmissionsByMonth(since));

        return new AdminStatsResponse(totalUsers, totalTeachers, totalStudents,
                totalExams, totalPublishedExams, totalSubmissions, recentUsers, recentExams,
                monthlyRegistrations, monthlySubmissions);
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
        AdminUserResponse response = mapToResponse(userRepository.save(user));
        auditLogService.log(AuditAction.USER_ROLE_CHANGED, "admin", "Admin", "USER", user.getEmail(), "Yeni rol: " + roleStr);
        return response;
    }

    @Transactional
    public AdminUserResponse toggleEnabled(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("İstifadəçi tapılmadı"));
        user.setEnabled(!user.isEnabled());
        AdminUserResponse response = mapToResponse(userRepository.save(user));
        auditLogService.log(AuditAction.USER_TOGGLED, "admin", "Admin", "USER", user.getEmail(), user.isEnabled() ? "Aktivləşdirildi" : "Deaktivləşdirildi");
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
        AdminExamResponse response = mapToExamResponse(examRepository.save(exam));
        auditLogService.log(exam.isSitePublished() ? AuditAction.EXAM_SITE_PUBLISHED : AuditAction.EXAM_SITE_UNPUBLISHED, "admin", "Admin", "EXAM", exam.getTitle(), null);
        return response;
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
        auditLogService.log(AuditAction.EXAM_DELETED, "admin", "Admin", "EXAM", examId.toString(), null);
        submissionRepository.deleteByExamId(examId);
        examRepository.deleteById(examId);
    }

    // ───────── Subject management ─────────

    @Transactional(readOnly = true)
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
        ExamSubject saved = subjectRepository.save(ExamSubject.builder()
                .name(trimmed)
                .isDefault(false)
                .build());
        auditLogService.log(AuditAction.SUBJECT_ADDED, "admin", "Admin", "SUBJECT", trimmed, null);
        return saved;
    }

    @Transactional
    public void deleteSubject(Long id) {
        ExamSubject subject = subjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (subject.isDefault()) {
            throw new BadRequestException("Default fənnlər silinə bilməz");
        }
        auditLogService.log(AuditAction.SUBJECT_DELETED, "admin", "Admin", "SUBJECT", subject.getName(), null);
        subjectRepository.deleteById(id);
    }

    @Transactional
    public SubjectTopic addTopicToSubject(Long subjectId, String name, String gradeLevel) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw new BadRequestException("Mövzu adı boş ola bilməz");
        ExamSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (subjectTopicRepository.existsBySubjectIdAndName(subjectId, trimmed)) {
            throw new BadRequestException("Bu mövzu artıq mövcuddur");
        }
        long count = subjectTopicRepository.countBySubjectId(subjectId);
        SubjectTopic topic = SubjectTopic.builder()
                .name(trimmed)
                .gradeLevel(gradeLevel)
                .orderIndex((int) count)
                .subject(subject)
                .build();
        return subjectTopicRepository.save(topic);
    }

    @Transactional
    public void removeTopicFromSubject(Long subjectId, Long topicId) {
        SubjectTopic topic = subjectTopicRepository.findById(topicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mövzu tapılmadı"));
        if (!topic.getSubject().getId().equals(subjectId)) {
            throw new BadRequestException("Bu mövzu bu fənnə aid deyil");
        }
        subjectTopicRepository.deleteById(topicId);
    }

    @Transactional
    public ExamSubject updateSubjectMetadata(Long id, String color, String iconEmoji, String description) {
        ExamSubject subject = subjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (color != null) subject.setColor(color);
        if (iconEmoji != null) subject.setIconEmoji(iconEmoji);
        if (description != null) subject.setDescription(description);
        return subjectRepository.save(subject);
    }

    @Transactional(readOnly = true)
    public SubjectStatsResponse getSubjectStats(Long subjectId) {
        if (!subjectRepository.existsById(subjectId)) {
            throw new ResourceNotFoundException("Fənn tapılmadı");
        }
        List<Object[]> rows = bankQuestionRepository.countBySubjectGroupByTopicAndDifficulty(subjectId);

        long totalQuestions = 0;
        Map<String, Long> byDifficulty = new HashMap<>();
        // topic -> difficulty -> count
        Map<String, Map<String, Long>> topicMap = new LinkedHashMap<>();

        for (Object[] row : rows) {
            String topic = (String) row[0];
            Difficulty diff = (Difficulty) row[1];
            long count = ((Number) row[2]).longValue();

            totalQuestions += count;

            String diffKey = diff != null ? diff.name() : "UNSET";
            byDifficulty.merge(diffKey, count, Long::sum);

            String topicKey = topic != null ? topic : "(Mövzusuz)";
            topicMap.computeIfAbsent(topicKey, k -> new HashMap<>()).merge(diffKey, count, Long::sum);
        }

        List<SubjectStatsResponse.TopicStat> byTopic = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> entry : topicMap.entrySet()) {
            long topicTotal = entry.getValue().values().stream().mapToLong(Long::longValue).sum();
            byTopic.add(new SubjectStatsResponse.TopicStat(entry.getKey(), topicTotal, entry.getValue()));
        }

        return new SubjectStatsResponse(totalQuestions, byDifficulty, byTopic);
    }

    // ───────── Exam assignment ─────────

    @Transactional
    public void assignExamToStudent(Long userId, Long examId) {
        User student = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("İstifadəçi tapılmadı"));
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        // Idempotent: skip if already purchased
        if (!examPurchaseRepository.existsByUserIdAndExamId(userId, examId)) {
            examPurchaseRepository.save(ExamPurchase.builder()
                    .user(student)
                    .exam(exam)
                    .amountPaid(BigDecimal.ZERO)
                    .build());
        }

        // Save to student's depot if not already there
        if (!studentSavedExamRepository.existsByStudentIdAndExamId(userId, examId)) {
            studentSavedExamRepository.save(StudentSavedExam.builder()
                    .student(student)
                    .exam(exam)
                    .build());
        }
    }

    // ───────── Notification broadcast ─────────

    @Transactional
    public NotificationLogResponse sendAdminNotification(AdminNotificationRequest req,
                                                          MultipartFile attachment,
                                                          String adminEmail) {
        // 1. Resolve target users
        List<User> targets = switch (req.targetType() == null ? "ALL" : req.targetType().toUpperCase()) {
            case "ROLE" -> {
                Role role = Role.valueOf(req.roleFilter().toUpperCase());
                yield userRepository.findByRole(role);
            }
            case "SELECTED" -> userRepository.findAllById(
                    req.userIds() != null ? req.userIds() : List.of());
            default -> userRepository.findAll();
        };

        List<String> channels = req.channels() != null ? req.channels() : List.of();
        String htmlBody = emailService.buildHtml(req.title(), req.description());

        // 2. Send to each user
        for (User user : targets) {
            if (channels.contains("SITE")) {
                NotificationType notifType = NotificationType.SYSTEM;
                if (req.type() != null) {
                    try {
                        notifType = NotificationType.valueOf(req.type().toUpperCase());
                    } catch (IllegalArgumentException ignored) {}
                }
                notificationService.send(user, req.title(), req.description(), notifType, req.actionUrl());
            }
            if (channels.contains("GMAIL") && user.getEmail() != null) {
                emailService.sendGmail(user.getEmail(), user.getFullName(),
                        req.title(), htmlBody, attachment);
            }
            if (channels.contains("SENDPULSE") && user.getEmail() != null) {
                emailService.sendSendPulse(user.getEmail(), user.getFullName(),
                        req.title(), htmlBody, attachment);
            }
        }

        // 3. Log
        NotificationLog logEntry = NotificationLog.builder()
                .title(req.title())
                .description(req.description())
                .channels(String.join(",", channels))
                .targetType(req.targetType())
                .roleFilter(req.roleFilter())
                .recipientCount(targets.size())
                .sentBy(adminEmail)
                .build();

        NotificationLogResponse logResponse = toLogResponse(notificationLogRepository.save(logEntry));
        auditLogService.log(AuditAction.NOTIFICATION_SENT, adminEmail, adminEmail, "NOTIFICATION", req.title(), "Alıcı sayı: " + targets.size());
        return logResponse;
    }

    @Transactional(readOnly = true)
    public Page<NotificationLogResponse> getNotificationHistory(Pageable pageable) {
        return notificationLogRepository.findAllByOrderBySentAtDesc(pageable)
                .map(this::toLogResponse);
    }

    private NotificationLogResponse toLogResponse(NotificationLog entry) {
        return new NotificationLogResponse(
                entry.getId(), entry.getTitle(), entry.getDescription(),
                entry.getChannels(), entry.getTargetType(), entry.getRoleFilter(),
                entry.getRecipientCount(), entry.getSentBy(), entry.getSentAt()
        );
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
                user.getPhoneNumber(),
                user.getRole(),
                user.getProfilePicture(),
                user.getCreatedAt(),
                user.isEnabled(),
                activePlanName
        );
    }

}
