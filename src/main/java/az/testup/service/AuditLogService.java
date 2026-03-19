package az.testup.service;

import az.testup.dto.response.AuditLogResponse;
import az.testup.entity.AuditLog;
import az.testup.enums.AuditAction;
import az.testup.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository repository;

    // Category mapping
    private static final Map<String, String> CATEGORY = Map.ofEntries(
        Map.entry("USER_LOGIN", "AUTH"),
        Map.entry("USER_LOGIN_FAILED", "AUTH"),
        Map.entry("USER_REGISTERED", "AUTH"),
        Map.entry("USER_ROLE_CHANGED", "USER"),
        Map.entry("USER_DELETED", "USER"),
        Map.entry("USER_TOGGLED", "USER"),
        Map.entry("USER_EXAM_ASSIGNED", "USER"),
        Map.entry("EXAM_CREATED", "EXAM"),
        Map.entry("EXAM_DELETED", "EXAM"),
        Map.entry("EXAM_SITE_PUBLISHED", "EXAM"),
        Map.entry("EXAM_SITE_UNPUBLISHED", "EXAM"),
        Map.entry("SUBJECT_ADDED", "CONTENT"),
        Map.entry("SUBJECT_DELETED", "CONTENT"),
        Map.entry("TOPIC_ADDED", "CONTENT"),
        Map.entry("TOPIC_DELETED", "CONTENT"),
        Map.entry("NOTIFICATION_SENT", "CONTENT"),
        Map.entry("SYSTEM_ERROR", "SYSTEM")
    );

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

    public Page<AuditLogResponse> getLogs(String actionStr, String category,
                                           String search, String period, Pageable pageable) {
        // Validate action string (pass as String to native query)
        String actionParam = null;
        if (actionStr != null && !actionStr.isBlank()) {
            try { AuditAction.valueOf(actionStr); actionParam = actionStr; } catch (Exception ignored) {}
        }

        LocalDateTime since = switch (period == null ? "" : period) {
            case "TODAY" -> LocalDateTime.now().toLocalDate().atStartOfDay();
            case "WEEK"  -> LocalDateTime.now().minusWeeks(1);
            case "MONTH" -> LocalDateTime.now().minusMonths(1);
            default      -> null;
        };

        Page<AuditLog> page = repository.search(
            actionParam,
            (search != null && !search.isBlank()) ? search : null,
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
