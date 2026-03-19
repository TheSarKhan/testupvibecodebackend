package az.testup.dto.request;

import lombok.Data;

@Data
public class GenerateQuestionsRequest {
    private Long subjectId;
    private String subjectName;
    private String topicName;
    private String difficulty;   // EASY / MEDIUM / HARD
    private String questionType; // MCQ / OPEN_AUTO / FILL_IN_THE_BLANK / MULTI_SELECT
    private int count;           // 1..10
}
