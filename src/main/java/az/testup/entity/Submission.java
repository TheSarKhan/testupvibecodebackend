package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "submissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Registered user (null if guest) */
    // Student loading is batched via a class-level @BatchSize on the User entity
    // (Hibernate 6 forbids @BatchSize on a @ManyToOne field). The teacher results
    // / statistics pages read student.getFullName() per submission, so batching
    // turns that N+1 into a handful of IN-queries.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private User student;

    /** Guest name (used when not logged in) */
    private String guestName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    /** Total score (calculated after grading) */
    private Double totalScore;

    /** Maximum possible score */
    private Double maxScore;

    /** Formula-based percentage score for template exams (0-100, null for non-template) */
    private Double templateScorePercent;

    /** Whether all auto-graded + manual questions are graded */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isFullyGraded = false;

    /** 1-5 star rating given by the student after the exam */
    private Integer rating;

    // BatchSize: mapToResponse / statistics iterate every submission's answers.
    // Without batching each submission triggers its own SELECT (N+1); batching
    // loads the answers for up to 100 submissions in a single query.
    @OneToMany(mappedBy = "submission", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 100)
    private List<Answer> answers = new ArrayList<>();

    private Instant startedAt;

    private Instant submittedAt;

    /** When true, this submission is hidden from teacher statistics (soft-delete for teacher view only) */
    @Column(nullable = false)
    @Builder.Default
    private Boolean hiddenFromTeacher = false;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
