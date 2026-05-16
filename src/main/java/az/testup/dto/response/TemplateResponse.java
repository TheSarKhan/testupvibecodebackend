package az.testup.dto.response;

import java.time.LocalDateTime;

public record TemplateResponse(
        Long id,
        String title,
        int subtitleCount,
        long examCount,
        LocalDateTime createdAt,
        String templateType
) {}
