package az.testup.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record DepotExamResponse(
        Long id,
        String title,
        String description,
        List<String> subjects,
        String shareLink,
        Integer questionCount,
        Integer durationMinutes,
        BigDecimal price,
        boolean isPaid,
        LocalDateTime savedAt
) {}
