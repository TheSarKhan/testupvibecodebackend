package az.testup.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "subject_topics")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubjectTopic {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** "1-4", "5-8", "9-11", "Buraxılış", or null = all grades */
    private String gradeLevel;

    private Integer orderIndex;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id", nullable = false)
    private ExamSubject subject;
}
