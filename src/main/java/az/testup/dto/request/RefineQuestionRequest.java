package az.testup.dto.request;

import lombok.Data;

/**
 * BUG-22: per-question AI refinement. Carries the question being polished,
 * the requested action and enough context to keep the replacement on-topic.
 */
@Data
public class RefineQuestionRequest {
    /** The question to refine, in the same shape /ai/generate-questions returns. */
    private BankQuestionRequest question;
    /** REGENERATE | EASIER | HARDER | REWORD */
    private String action;
    private Long subjectId;
    private String subjectName;
    private String topicName;     // optional
    private String instructions;  // optional free-form teacher guidance
}
