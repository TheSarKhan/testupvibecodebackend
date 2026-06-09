package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Admin-managed category for grouping subjects in the exam-creation picker.
 * Replaces the short-lived plain-string ExamSubject.category column (V30):
 * categories are now rows the admin can create, rename, order and delete.
 */
@Entity
@Table(name = "subject_categories")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubjectCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /** Pill order in the picker (ascending; nulls last). */
    private Integer orderIndex;

    /** Optional hex accent, e.g. "#6366f1". */
    private String color;

    /** Seeded categories cannot be deleted. */
    @Column(nullable = false)
    @Builder.Default
    private boolean isDefault = false;
}
