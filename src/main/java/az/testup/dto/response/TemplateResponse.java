package az.testup.dto.response;

import java.time.LocalDateTime;

public record TemplateResponse(
        Long id,
        String title,
        int subtitleCount,
        LocalDateTime createdAt
) {}
