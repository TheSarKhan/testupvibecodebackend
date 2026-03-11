package az.testup.dto.response;

import az.testup.enums.Subject;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DepotExamResponse(
        Long id,
        String title,
        String description,
        Subject subject,
        String shareLink,
        Integer questionCount,
        Integer durationMinutes,
        BigDecimal price,
        boolean isPaid,
        LocalDateTime savedAt
) {}
