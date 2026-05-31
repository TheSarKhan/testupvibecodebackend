package az.testup.dto.request;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class GenerateExamRequest {
    private String subjectName;
    // Legacy single-topic field. Kept for clients that haven't migrated yet —
    // when {@link #topicNames} is empty/null we fall back to this value.
    private String topicName;      // optional
    // Preferred new field: zero or more topics. If multiple, the AI is told
    // to either distribute questions across them or, for a single-question
    // exam, combine/pick from them.
    private List<String> topicNames;
    private String difficulty;     // EASY / MEDIUM / HARD, default MEDIUM
    private Map<String, Integer> typeCounts; // e.g. {"MCQ": 5, "OPEN_AUTO": 2}
    // Optional exam title for background (async) generation. When blank, a
    // default "AI İmtahanı — {subject} · {date}" title is generated.
    private String title;
}
