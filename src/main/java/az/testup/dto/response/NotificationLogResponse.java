package az.testup.dto.response;

import java.time.LocalDateTime;

public record NotificationLogResponse(
        Long id,
        String title,
        String description,
        String channels,
        String targetType,
        String roleFilter,
        Integer recipientCount,
        String sentBy,
        LocalDateTime sentAt
) {}
