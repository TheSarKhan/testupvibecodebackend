package az.testup.dto.request;

import lombok.Data;

@Data
public class GenerateQuestionsRequest {
    private Long subjectId;
    private String subjectName;
    private String topicName;
    private String difficulty;   // EASY / MEDIUM / HARD
    private String questionType; // MCQ / TRUE_FALSE / MULTI_SELECT / MATCHING / OPEN_AUTO / FILL_IN_THE_BLANK
    private int count;           // 1..10

    // ── BUG-21 optional prompt modifiers (carried through from exam-level request) ──
    private String instructions; // free-form teacher guidance
    private String gradeLevel;   // e.g. "7-ci sinif", "Universitet"
    private String language;     // AZ / EN / RU — null = implicit (subject-driven)

    // ── BUG-22: optional seed — generate variations similar to this question ──
    private String seedQuestion;
}
