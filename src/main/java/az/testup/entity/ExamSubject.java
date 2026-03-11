package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "exam_subjects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamSubject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /** Default subjects cannot be deleted */
    @Column(nullable = false)
    @Builder.Default
    private boolean isDefault = false;
}
