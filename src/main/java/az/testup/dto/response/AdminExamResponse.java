package az.testup.dto.response;

import az.testup.enums.ExamStatus;
import az.testup.enums.Subject;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdminExamResponse(
        Long id,
        String title,
        String teacherName,
        String teacherEmail,
        Subject subject,
        ExamStatus status,
        boolean sitePublished,
        BigDecimal price,
        int questionCount,
        String shareLink,
        LocalDateTime createdAt
) {}
