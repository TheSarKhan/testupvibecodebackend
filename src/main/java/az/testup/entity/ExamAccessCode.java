package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * One-time-use access code for a PRIVATE exam.
 * Each code is generated for one student, can only be used once, and expires in 12 hours.
 */
@Entity
@Table(name = "exam_access_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamAccessCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    /** 6-digit numeric code */
    @Column(nullable = false, unique = true)
    private String code;

    /** When this code expires (12 hours after creation) */
    @Column(nullable = false)
    private Instant expiresAt;

    /** True once a student has used this code to start an exam */
    @Column(nullable = false)
    @Builder.Default
    private boolean used = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
