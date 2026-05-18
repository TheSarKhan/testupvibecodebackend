package az.testup.entity;

import az.testup.enums.AuditAction;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_created", columnList = "created_at"),
    @Index(name = "idx_audit_action", columnList = "action"),
    @Index(name = "idx_audit_actor", columnList = "actor_email")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Stored as VARCHAR via EnumType.STRING. The explicit columnDefinition
     * prevents Hibernate from auto-generating a CHECK constraint that becomes
     * stale every time we add a new value to {@link AuditAction}. Validation
     * happens at the JVM layer (enum parsing), so a DB-level CHECK only causes
     * production failures when the enum grows.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64, columnDefinition = "varchar(64)")
    private AuditAction action;

    /** Who performed the action */
    private String actorEmail;
    private String actorName;

    /** What was affected */
    private String targetType;   // e.g. "USER", "EXAM", "SUBJECT"
    private String targetName;   // e.g. username, exam title, subject name

    /** Optional extra detail */
    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }
}
