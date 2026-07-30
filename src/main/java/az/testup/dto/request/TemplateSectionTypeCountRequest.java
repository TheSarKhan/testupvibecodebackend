package az.testup.dto.request;

public record TemplateSectionTypeCountRequest(
        String questionType,
        Integer count,
        String passageType,
        /** Rows sharing (passageType, passageGroup) form one passage. Null = single passage of that type. */
        Integer passageGroup
) {}
