package az.testup.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class SubscriptionPlanResponse {

    private Long id;
    private String name;
    private Integer level;
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

    private Integer monthlyAiQuestionLimit;
    private boolean useAiExamGeneration;

    /** Admin-controlled toggle. When false, the plan is hidden from the public pricing page. */
    private boolean visible;

    /** Stripe-style billing options: one entry per supported duration (1/3/6/12). */
    private List<PlanPriceResponse> prices;

    // ── Deprecated, frontend-compat shim (remove in phase 2) ──────────────
    // The pricing page still reads a flat price/durationMonths off each plan.
    // We expose the 1-month price here so the old UI keeps working until it
    // migrates to `prices`.
    /** @deprecated use {@link #prices}. */
    @Deprecated
    private Double price;
    /** @deprecated use {@link #prices}. Always 1 (the base tier). */
    @Deprecated
    private Integer durationMonths;
}
