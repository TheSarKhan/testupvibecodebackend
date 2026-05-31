package az.testup.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A teacher's reusable topic within a bank subject. Created lazily the first
 * time a teacher saves a question carrying that topic name, then surfaced in
 * the question editor's topic picker so the same name can be reused. Keyed by
 * (subject, owner, name) so each teacher keeps a private list even on a shared
 * global subject. {@code lastUsedAt} drives the "recently used" group.
 */
@Entity
@Table(name = "bank_topics",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_bank_topics_subject_owner_name",
                columnNames = {"bank_subject_id", "owner_id", "name"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BankTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_subject_id", nullable = false)
    private BankSubject subject;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /** UTC instant the topic was last attached to a saved question. */
    private Instant lastUsedAt;

    @Column(updatable = false)
    private Instant createdAt;
}
