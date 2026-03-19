package az.testup.dto.request;

import java.util.List;

public record CreateCollaborativeExamRequest(
        String title,
        String description,
        Integer durationMinutes,
        String examType,       // "FREE" or "TEMPLATE"
        Long templateId,       // required when examType = TEMPLATE
        List<CollaboratorAssignment> collaborators
) {}
