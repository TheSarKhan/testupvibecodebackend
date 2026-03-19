package az.testup.dto.response;

import az.testup.enums.CollaboratorStatus;

import java.time.LocalDateTime;
import java.util.List;

public record CollaboratorResponse(
        Long id,
        Long collaborativeExamId,
        String examTitle,
        Long teacherId,
        String teacherName,
        String teacherEmail,
        List<String> subjects,
        CollaboratorStatus status,
        String adminComment,
        Long draftExamId,
        Integer draftQuestionCount,
        LocalDateTime submittedAt,
        LocalDateTime createdAt,
        /** Template sections assigned to this teacher (empty = free mode) */
        List<CollaboratorSectionInfo> templateSections
) {}
