package az.testup.dto.response;

import az.testup.enums.ExamStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AdminExamResponse(
        Long id,
        String title,
        String teacherName,
        String teacherEmail,
        List<String> subjects,
        ExamStatus status,
        boolean sitePublished,
        BigDecimal price,
        int questionCount,
        String shareLink,
        LocalDateTime createdAt
) {}
