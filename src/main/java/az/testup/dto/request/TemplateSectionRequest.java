package az.testup.dto.request;

import java.util.List;

public record TemplateSectionRequest(
        Long id,
        String subjectName,
        List<TemplateSectionTypeCountRequest> typeCounts,
        String formula,
        Integer orderIndex,
        String pointGroups
) {}
