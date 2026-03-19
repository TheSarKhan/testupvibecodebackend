package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "exam_subjects")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
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

    // Metadata
    private String color;       // hex e.g. "#6366f1"
    private String iconEmoji;   // e.g. "📐"
    @Column(columnDefinition = "TEXT")
    private String description;

    @OneToMany(mappedBy = "subject", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SubjectTopic> topics = new ArrayList<>();
}
