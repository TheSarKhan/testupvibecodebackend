package az.testup.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class SubscriptionPlanRequest {

    @NotBlank(message = "Plan name is required")
    private String name;

    @Min(value = 0, message = "Level cannot be negative")
    private Integer level = 0;

    private String description;

    // Limits
    private Integer monthlyExamLimit;
    private Integer maxQuestionsPerExam;
    private Integer maxSavedExamsLimit;
    private Integer maxParticipantsPerExam;

    // Features
    private boolean studentResultAnalysis;
    private boolean examEditing;
    private boolean addImage;
    private boolean addPassageQuestion;
    private boolean downloadPastExams;
    private boolean downloadAsPdf;
    private boolean multipleSubjects;
    private boolean useTemplateExams;
    private boolean manualChecking;
    private boolean selectExamDuration;
    private boolean useQuestionBank;
    private boolean createQuestionBank;
    private boolean importQuestionsFromPdf;

    /** Monthly AI question generation limit. null/0 = disabled, -1 = unlimited */
    private Integer monthlyAiQuestionLimit;
    private boolean useAiExamGeneration;

    /** Defaults to true so plans created without this field stay visible. */
    private boolean visible = true;

    /** Billing options for this tier (1/3/6/12-month prices). Replaces the old flat price. */
    @Valid
    private List<PlanPriceRequest> prices;
}
