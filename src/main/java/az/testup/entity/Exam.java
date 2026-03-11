package az.testup.entity;

import az.testup.enums.ExamStatus;
import az.testup.enums.ExamType;
import az.testup.enums.ExamVisibility;
import az.testup.enums.Subject;
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Subject subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExamVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExamType examType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExamStatus status;

    /** 6-digit access code for PRIVATE exams (teacher-generated, time-limited) */
    private String accessCode;

    /** Expiry timestamp of the current access code (12 hours after generation) */
    private LocalDateTime accessCodeExpiresAt;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private User teacher;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private Template template;

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
