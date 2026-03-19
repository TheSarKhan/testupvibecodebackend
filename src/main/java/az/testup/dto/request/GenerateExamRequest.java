package az.testup.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class GenerateExamRequest {
    private String subjectName;
    private String topicName;      // optional
    private String difficulty;     // EASY / MEDIUM / HARD, default MEDIUM
    private Map<String, Integer> typeCounts; // e.g. {"MCQ": 5, "OPEN_AUTO": 2}
}
