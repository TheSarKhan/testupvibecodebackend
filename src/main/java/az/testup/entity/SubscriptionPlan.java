package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "subscription_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private Double price;

    @Column(nullable = false)
    private Integer level = 0; // 0=Free, 1=Basic, 2=Limitsiz, etc.

    @Column(columnDefinition = "TEXT")
    private String description;

    // Limits
    private Integer monthlyExamLimit;
    private Integer maxQuestionsPerExam;
    private Integer maxSavedExamsLimit;
    private Integer maxParticipantsPerExam;

    // Features (Flags)
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

    /** Whether AI exam generation is allowed */
    private boolean useAiExamGeneration;

    /**
     * Billing duration in months for this plan row. Each "Basic / Limitsiz × 1/3/6/12 ay"
     * combination lives as its own row so the storefront can show distinct
     * SKUs and the payment flow doesn't have to multiply at runtime.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer durationMonths = 1;

    /**
     * Admin-controlled visibility. When false, the plan is hidden from the
     * public pricing page but admins can still assign it to users manually.
     * Lets us A/B promotional tiers, sunset legacy SKUs, or pull a plan
     * temporarily without deleting it.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean visible = true;
}
