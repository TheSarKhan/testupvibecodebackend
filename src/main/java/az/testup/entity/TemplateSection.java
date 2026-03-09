package az.testup.entity;

import az.testup.enums.QuestionType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "template_sections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionType questionType;

    /** Number of questions of this type */
    @Column(nullable = false)
    private Integer questionCount;

    /** Points per question in this section */
    @Column(nullable = false)
    private Double pointsPerQuestion;

    /** Display order */
    private Integer orderIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private Template template;
}
