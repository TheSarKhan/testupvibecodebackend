package az.testup.dto.response;

public record CollaboratorSectionInfo(
        Long id,
        String subjectName,
        int questionCount,
        String formula
) {}
