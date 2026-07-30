package az.testup.dto.response;

public record TemplateSectionTypeCountResponse(
        Long id,
        String questionType,
        Integer count,
        Integer orderIndex,
        String passageType,
        /** Rows sharing (passageType, passageGroup) form one passage. Null = single passage of that type. */
        Integer passageGroup
) {}
