package az.testup.entity;

import az.testup.enums.QuestionType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "template_section_type_counts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateSectionTypeCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id", nullable = false)
    private TemplateSection section;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionType questionType;

    /** Passage type group this row belongs to (LISTENING / TEXT), or null for standalone questions. */
    @Column(name = "passage_type")
    private String passageType;

    @Column(nullable = false)
    private Integer count;

    @Column(nullable = false)
    private Integer orderIndex;
}
