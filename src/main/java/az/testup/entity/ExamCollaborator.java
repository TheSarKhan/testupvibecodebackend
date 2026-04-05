package az.testup.entity;

import az.testup.enums.CollaboratorStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "exam_collaborators",
        uniqueConstraints = @UniqueConstraint(columnNames = {"collaborative_exam_id", "teacher_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamCollaborator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The main collaborative exam (owned by admin) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collaborative_exam_id", nullable = false)
    private Exam collaborativeExam;

    /** The teacher assigned to this portion */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private User teacher;

    /** Subjects this teacher is responsible for (free mode) */
    @ElementCollection
    @CollectionTable(name = "exam_collaborator_subjects", joinColumns = @JoinColumn(name = "collaborator_id"))
    @Column(name = "subject")
    @Builder.Default
    private List<String> subjects = new ArrayList<>();

    /** Template section IDs assigned to this teacher (template mode; empty = free mode) */
    @ElementCollection
    @CollectionTable(name = "exam_collaborator_section_ids", joinColumns = @JoinColumn(name = "collaborator_id"))
    @Column(name = "template_section_id")
    @Builder.Default
    private List<Long> templateSectionIds = new ArrayList<>();

    /** The teacher's personal draft exam workspace (lazily created on first open) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "draft_exam_id")
    private Exam draftExam;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CollaboratorStatus status = CollaboratorStatus.ASSIGNED;

    /** Admin's comment when rejecting (nullable) */
    @Column(columnDefinition = "TEXT")
    private String adminComment;

    private Instant submittedAt;

    @Column(updatable = false, nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
