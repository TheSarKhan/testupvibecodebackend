package az.testup.dto.response;

public record TemplateSectionTypeCountResponse(
        Long id,
        String questionType,
        Integer count,
        Integer orderIndex,
        String passageType
) {}
