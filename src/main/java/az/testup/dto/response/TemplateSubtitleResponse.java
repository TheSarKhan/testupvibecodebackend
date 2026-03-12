package az.testup.dto.response;

import java.util.List;

public record TemplateSubtitleResponse(
        Long id,
        String subtitle,
        Integer orderIndex,
        List<TemplateSectionResponse> sections
) {}
