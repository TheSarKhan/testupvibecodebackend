package az.testup.dto.response;

import java.time.LocalDateTime;

public record TemplateStatsResponse(
        long totalTemplates,
        long totalExamUsage,
        TopTemplate topTemplate,
        RecentTemplate mostRecent
) {
    public record TopTemplate(Long id, String title, long examCount) {}
    public record RecentTemplate(Long id, String title, LocalDateTime createdAt) {}
}
