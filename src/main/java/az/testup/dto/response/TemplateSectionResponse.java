package az.testup.dto.response;

import java.util.List;

public record TemplateSectionResponse(
        Long id,
        String subjectName,
        Integer questionCount,
        List<TemplateSectionTypeCountResponse> typeCounts,
        String formula,
        Integer orderIndex,
        String templateTitle,
        String subtitleName,
        String pointGroups,
        Double maxScore,
        Boolean allowCustomPoints
) {}
