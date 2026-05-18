package az.testup.entity;

import az.testup.enums.QuestionReviewStatus;
import az.testup.enums.QuestionType;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "questions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Question text — supports plain text and LaTeX/MathML */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String attachedImage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionType questionType;

    /** Points for this question */
    @Column(nullable = false)
    private Double points;

    /** Display order within the exam */
    private Integer orderIndex;

    /** Correct answer for auto-graded open questions */
    @Column(columnDefinition = "TEXT")
    private String correctAnswer;

    /** Sample answer or correct blanks JSON for Fill-in-the-blank */
    @Column(columnDefinition = "TEXT")
    private String sampleAnswer;

    /** Subject section this question belongs to (null = first/main section) */
    @Column(name = "subject_group")
    private String subjectGroup;

    /**
     * Per-question review state when the question lives in a collaborative-exam draft.
     * Null on parent-exam questions and standalone exams.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", length = 20)
    private QuestionReviewStatus reviewStatus;

    /** Admin's comment when reviewStatus = REJECTED. */
    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    /**
     * When this question is a collaborative-draft question that was once approved, the id of
     * the parent-exam copy it was promoted into. Re-approval after a re-edit overwrites that
     * row in place instead of creating a duplicate parent question.
     */
    @Column(name = "parent_copy_id")
    private Long parentCopyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    /** Null for standalone questions; non-null when question belongs to a passage group */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passage_id")
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.SET_NULL)
    private Passage passage;

    /** Options for MCQ / True-False questions */
    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Option> options = new ArrayList<>();

    /** Matching pairs for MATCHING type questions */
    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<MatchingPair> matchingPairs = new ArrayList<>();
}
