package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;
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

    @OneToMany(mappedBy = "submission", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Answer> answers = new ArrayList<>();

    private Instant startedAt;

    private Instant submittedAt;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
