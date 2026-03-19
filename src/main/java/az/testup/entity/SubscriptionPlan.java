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
}
