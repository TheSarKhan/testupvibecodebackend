package az.testup.dto.request;

import java.util.List;

public record AdminNotificationRequest(
        String title,
        String description,
        List<String> channels,   // SITE, GMAIL, SENDPULSE
        String targetType,       // ALL, ROLE, SELECTED
        String roleFilter,       // STUDENT, TEACHER, ADMIN (ROLE üçün)
        List<Long> userIds,      // SELECTED üçün
        String type,             // Notification növü: ANNOUNCEMENT, WARNING, vb.
        String actionUrl         // Yönləndirələcək link
) {}
