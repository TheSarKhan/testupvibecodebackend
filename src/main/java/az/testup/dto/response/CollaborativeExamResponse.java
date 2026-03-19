package az.testup.dto.response;

import az.testup.enums.ExamStatus;

import java.time.LocalDateTime;
import java.util.List;

public record CollaborativeExamResponse(
        Long id,
        String title,
        String description,
        Integer durationMinutes,
        ExamStatus status,
        boolean sitePublished,
        String shareLink,
        List<CollaboratorResponse> collaborators,
        int totalQuestions,
        LocalDateTime createdAt
) {}
