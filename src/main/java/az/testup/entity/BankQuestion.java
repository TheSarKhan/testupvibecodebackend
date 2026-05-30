package az.testup.entity;

import az.testup.enums.QuestionType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "bank_questions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String attachedImage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionType questionType;

    @Column(nullable = false)
    private Double points;

    private Integer orderIndex;

    @Column(columnDefinition = "TEXT")
    private String correctAnswer;

    /** Optional topic within the subject (e.g. "Cəbr") */
    private String topic;

    @Enumerated(EnumType.STRING)
    private az.testup.enums.Difficulty difficulty;

    /** "1-4", "5-8", "9-11", "Buraxılış", or null = all grades */
    @Column(name = "grade_level", length = 32)
    private String gradeLevel;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "bank_question_tags",
            joinColumns = @JoinColumn(name = "bank_question_id"))
    @Column(name = "tag", length = 64)
    @Builder.Default
    private Set<String> tags = new HashSet<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_subject_id", nullable = false)
    private BankSubject subject;

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BankOption> options = new ArrayList<>();

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BankMatchingPair> matchingPairs = new ArrayList<>();

    // UTC Instant so the timestamp serialises zone-marked (…Z) and the question
    // bank's "last added" relative time is computed against a real instant
    // instead of a naked server-local string (BUG-10).
    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
