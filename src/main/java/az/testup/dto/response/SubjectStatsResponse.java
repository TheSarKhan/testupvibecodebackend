package az.testup.dto.response;

import java.util.List;
import java.util.Map;

public record SubjectStatsResponse(
    long totalQuestions,
    Map<String, Long> byDifficulty,   // "EASY"->5, "MEDIUM"->10, etc.
    List<TopicStat> byTopic
) {
    public record TopicStat(String topic, long count, Map<String, Long> byDifficulty) {}
}
