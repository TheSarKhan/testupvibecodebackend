package az.testup.dto.request;

public record TemplateSectionTypeCountRequest(
        String questionType,
        Integer count,
        String passageType
) {}
