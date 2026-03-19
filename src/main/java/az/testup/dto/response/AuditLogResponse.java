package az.testup.dto.response;

public record AuditLogResponse(
    Long id,
    String action,
    String category,
    String actorEmail,
    String actorName,
    String targetType,
    String targetName,
    String details,
    String createdAt
) {}
