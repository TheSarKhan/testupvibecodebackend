package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
// One Answer per (submission, question). The upsert in SubmissionService relies
// on this unique constraint via ON CONFLICT; it was historically added only in
// migration V23, so a Hibernate-built schema (ddl-auto) lacked it and the
// upsert failed. Declaring it here keeps entity and DB schema in sync.
@Table(name = "answers", uniqueConstraints = @UniqueConstraint(
        name = "uq_answers_submission_question",
        columnNames = {"submission_id", "question_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Answer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    /** Student's answer text (for open questions) */
    @Column(columnDefinition = "TEXT")
    private String answerText;

    /** Selected option ID (for MCQ / True-False) */
    private Long selectedOptionId;

    /** JSON storing matching pairs selected by student */
    @Column(columnDefinition = "TEXT")
    private String matchingAnswerJson;

    /** JSON storing multiple selected option IDs (for MULTI_SELECT) */
    @Column(columnDefinition = "TEXT")
    private String selectedOptionIdsJson;

    /** JSON snapshot of the question at the time of submission (for versioning) */
    @Column(columnDefinition = "TEXT")
    private String questionSnapshot;

    /** Score awarded for this answer */
    private Double score;

    /** Whether this answer has been graded (relevant for OPEN_MANUAL) */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isGraded = false;

    /** Teacher feedback for manual grading */
    @Column(columnDefinition = "TEXT")
    private String feedback;

    /** Student-uploaded image for open question answers (base64) */
    @Column(columnDefinition = "TEXT")
    private String answerImage;
}

