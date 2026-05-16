package az.testup.dto.response;

import java.util.List;

public record TagStatsResponse(
        long totalTags,
        long totalTagUsages,
        long untaggedExamCount,
        List<TagResponse> topTags
) {}
