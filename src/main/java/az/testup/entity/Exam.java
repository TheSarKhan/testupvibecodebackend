package az.testup.entity;

import az.testup.enums.ExamStatus;
import az.testup.enums.ExamType;
import az.testup.enums.ExamVisibility;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "exams")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String description;

    @ElementCollection
    @CollectionTable(name = "exam_subject_list", joinColumns = @JoinColumn(name = "exam_id"))
    @Column(name = "subject")
    @Builder.Default
    private List<String> subjects = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExamVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExamType examType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExamStatus status;

    /** Unique share link identifier */
    @Column(unique = true, nullable = false)
    private String shareLink;

    /** Duration in minutes (nullable = unlimited) */
    private Integer durationMinutes;

    /** Price in AZN (null = free) */
    private java.math.BigDecimal price;

    /** Whether this exam is published to the public site catalog by admin */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean sitePublished = false;

    /** If true, this is a collaborative exam managed by admin with multiple teacher contributors */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean isCollaborative = false;

    /** Non-null when this exam is a teacher's draft workspace for a collaborative exam */
    @Column(name = "collaborative_parent_id")
    private Long collaborativeParentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private User teacher;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private Template template;

    /** Which template section (subject) this exam fulfills — kept for backward compat (first section) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_section_id")
    private TemplateSection templateSection;

    /** All template sections assigned to this exam (multi-subject template support) */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "exam_template_sections",
        joinColumns = @JoinColumn(name = "exam_id"),
        inverseJoinColumns = @JoinColumn(name = "section_id")
    )
    @OrderBy("orderIndex ASC")
    @Builder.Default
    private List<TemplateSection> templateSections = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "exam_tags", joinColumns = @JoinColumn(name = "exam_id"))
    @Column(name = "tag")
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @OneToMany(mappedBy = "exam", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Question> questions = new ArrayList<>();

    @OneToMany(mappedBy = "exam", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Passage> passages = new ArrayList<>();

    /** Soft-delete flag — deleted exams are hidden from lists but submissions remain intact */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean deleted = false;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
