package az.testup.service;

import az.testup.dto.response.AuditLogResponse;
import az.testup.entity.AuditLog;
import az.testup.entity.User;
import az.testup.enums.AuditAction;
import az.testup.repository.AuditLogRepository;
import az.testup.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository repository;
    private final UserRepository userRepository;

    // Category mapping (action -> category shown in admin UI)
    private static final Map<String, String> CATEGORY;
    static {
        Map<String, String> m = new HashMap<>();
        // AUTH
        m.put("USER_LOGIN", "AUTH");
        m.put("USER_LOGIN_FAILED", "AUTH");
        m.put("USER_REGISTERED", "AUTH");
        m.put("PASSWORD_CHANGED", "AUTH");
        m.put("PASSWORD_RESET_REQUESTED", "AUTH");
        m.put("PASSWORD_RESET_COMPLETED", "AUTH");
        // USER
        m.put("USER_ROLE_CHANGED", "USER");
        m.put("USER_DELETED", "USER");
        m.put("USER_TOGGLED", "USER");
        m.put("USER_EXAM_ASSIGNED", "USER");
        // EXAM
        m.put("EXAM_CREATED", "EXAM");
        m.put("EXAM_UPDATED", "EXAM");
        m.put("EXAM_DELETED", "EXAM");
        m.put("EXAM_STATUS_CHANGED", "EXAM");
        m.put("EXAM_SITE_PUBLISHED", "EXAM");
        m.put("EXAM_SITE_UNPUBLISHED", "EXAM");
        m.put("EXAM_PRICE_CHANGED", "EXAM");
        m.put("EXAM_ACCESS_CODE_GENERATED", "EXAM");
        m.put("EXAM_PURCHASED", "EXAM");
        m.put("EXAM_PDF_DOWNLOADED", "EXAM");
        m.put("EXAM_RESULTS_EXPORTED", "EXAM");
        m.put("EXAM_STARTED", "EXAM");
        m.put("EXAM_SUBMITTED", "EXAM");
        m.put("SUBMISSION_MANUAL_GRADED", "EXAM");
        m.put("SUBMISSION_HIDDEN", "EXAM");
        m.put("COLLABORATIVE_EXAM_CREATED", "EXAM");
        m.put("COLLABORATIVE_COLLABORATOR_ADDED", "EXAM");
        m.put("COLLABORATIVE_DRAFT_SUBMITTED", "EXAM");
        m.put("COLLABORATIVE_DRAFT_APPROVED", "EXAM");
        m.put("COLLABORATIVE_DRAFT_REJECTED", "EXAM");
        // CONTENT
        m.put("SUBJECT_ADDED", "CONTENT");
        m.put("SUBJECT_DELETED", "CONTENT");
        m.put("SUBJECT_UPDATED", "CONTENT");
        m.put("TOPIC_ADDED", "CONTENT");
        m.put("TOPIC_DELETED", "CONTENT");
        m.put("BANNER_CREATED", "CONTENT");
        m.put("BANNER_UPDATED", "CONTENT");
        m.put("BANNER_DELETED", "CONTENT");
        m.put("TAG_CREATED", "CONTENT");
        m.put("TAG_DELETED", "CONTENT");
        m.put("TEMPLATE_CREATED", "CONTENT");
        m.put("TEMPLATE_UPDATED", "CONTENT");
        m.put("TEMPLATE_DELETED", "CONTENT");
        m.put("TEMPLATE_SUBTITLE_CREATED", "CONTENT");
        m.put("TEMPLATE_SUBTITLE_UPDATED", "CONTENT");
        m.put("TEMPLATE_SUBTITLE_DELETED", "CONTENT");
        m.put("TEMPLATE_SECTION_CREATED", "CONTENT");
        m.put("TEMPLATE_SECTION_UPDATED", "CONTENT");
        m.put("TEMPLATE_SECTION_DELETED", "CONTENT");
        m.put("BANK_SUBJECT_CREATED", "CONTENT");
        m.put("BANK_SUBJECT_UPDATED", "CONTENT");
        m.put("BANK_SUBJECT_DELETED", "CONTENT");
        m.put("BANK_QUESTION_CREATED", "CONTENT");
        m.put("BANK_QUESTION_UPDATED", "CONTENT");
        m.put("BANK_QUESTION_DELETED", "CONTENT");
        m.put("NOTIFICATION_SENT", "CONTENT");
        m.put("CONTACT_READ", "CONTENT");
        m.put("CONTACT_REPLIED", "CONTENT");
        m.put("CONTACT_DELETED", "CONTENT");
        // AI
        m.put("AI_QUESTIONS_GENERATED", "AI");
        m.put("AI_EXAM_GENERATED", "AI");
        // PAYMENT
        m.put("SUBSCRIPTION_PURCHASED", "PAYMENT");
        m.put("SUBSCRIPTION_SWITCHED", "PAYMENT");
        m.put("SUBSCRIPTION_ASSIGNED_MANUAL", "PAYMENT");
        m.put("SUBSCRIPTION_CANCELLED", "PAYMENT");
        m.put("SUBSCRIPTION_GIFTED", "PAYMENT");
        m.put("PLAN_CREATED", "PAYMENT");
        m.put("PLAN_UPDATED", "PAYMENT");
        m.put("PLAN_DELETED", "PAYMENT");
        // SYSTEM
        m.put("SYSTEM_ERROR", "SYSTEM");
        CATEGORY = Map.copyOf(m);
    }

    @Async
    public void log(AuditAction action, String actorEmail, String actorName,
                    String targetType, String targetName, String details) {
        try {
            repository.save(AuditLog.builder()
                .action(action)
                .actorEmail(actorEmail)
                .actorName(actorName)
                .targetType(targetType)
                .targetName(targetName)
                .details(details)
                .build());
        } catch (Exception e) {
            log.warn("Audit log yazılarkən xəta: {}", e.getMessage());
        }
    }

    // Convenience overload without details
    public void log(AuditAction action, String actorEmail, String actorName,
                    String targetType, String targetName) {
        log(action, actorEmail, actorName, targetType, targetName, null);
    }

    /**
     * Log an action using the current SecurityContext to resolve the actor.
     * Falls back to "system" when no authentication is present.
     */
    public void logCurrent(AuditAction action, String targetType, String targetName, String details) {
        String email = "system";
        String name = "system";
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserDetails ud) {
                email = ud.getUsername();
                final String finalEmail = email;
                name = userRepository.findByEmail(email)
                        .map(User::getFullName)
                        .orElse(finalEmail);
            }
        } catch (Exception ignored) {}
        log(action, email, name, targetType, targetName, details);
    }

    public void logCurrent(AuditAction action, String targetType, String targetName) {
        logCurrent(action, targetType, targetName, null);
    }

    public Page<AuditLogResponse> getLogs(String actionStr, String category,
                                           String search, String period, Pageable pageable) {
        return getLogs(actionStr, category, search, period, null, null, pageable);
    }

    public Page<AuditLogResponse> getLogs(String actionStr, String category, String search,
                                           String period, String actor, String targetType,
                                           Pageable pageable) {
        String actionParam = null;
        if (actionStr != null && !actionStr.isBlank()) {
            try { AuditAction.valueOf(actionStr); actionParam = actionStr; } catch (Exception ignored) {}
        }

        String actionsCsv = null;
        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)) {
            String catUpper = category.toUpperCase();
            actionsCsv = CATEGORY.entrySet().stream()
                    .filter(e -> catUpper.equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
        }

        LocalDateTime since = switch (period == null ? "" : period) {
            case "TODAY" -> LocalDateTime.now().toLocalDate().atStartOfDay();
            case "WEEK"  -> LocalDateTime.now().minusWeeks(1);
            case "MONTH" -> LocalDateTime.now().minusMonths(1);
            default      -> null;
        };

        Page<AuditLog> page = repository.search(
            actionParam,
            actionsCsv,
            (search != null && !search.isBlank()) ? search : null,
            (actor != null && !actor.isBlank()) ? actor : null,
            (targetType != null && !targetType.isBlank()) ? targetType : null,
            since,
            pageable
        );

        return page.map(this::toResponse);
    }

    private AuditLogResponse toResponse(AuditLog a) {
        return new AuditLogResponse(
            a.getId(),
            a.getAction().name(),
            CATEGORY.getOrDefault(a.getAction().name(), "SYSTEM"),
            a.getActorEmail(),
            a.getActorName(),
            a.getTargetType(),
            a.getTargetName(),
            a.getDetails(),
            a.getCreatedAt() != null ? a.getCreatedAt().toString() : null
        );
    }
}
