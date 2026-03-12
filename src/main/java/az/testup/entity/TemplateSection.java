package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

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

    /** Subject name for this section, e.g. "Riyaziyyat" */
    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    /** Fixed number of questions for this section (teacher cannot add/remove) */
    @Column(nullable = false)
    private Integer questionCount;

    /**
     * Scoring formula using variables:
     * a=MCQ/TF correct, b=MCQ/TF wrong, c=MCQ/TF blank
     * d=MULTI_SELECT correct, e=MULTI_SELECT wrong
     * f=OPEN_AUTO correct, g=OPEN_AUTO wrong
     * l=OPEN_MANUAL correct, m=OPEN_MANUAL wrong
     * h=FILL_IN_THE_BLANK correct, i=FILL_IN_THE_BLANK wrong
     * j=MATCHING correct, k=MATCHING wrong
     * n=total question count
     * Example: "a*1.5 - b*0.5"
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String formula;

    @OneToMany(mappedBy = "section", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("orderIndex ASC")
    @Builder.Default
    private List<TemplateSectionTypeCount> typeCounts = new ArrayList<>();

    /** Display order within template */
    @Column(nullable = false)
    private Integer orderIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subtitle_id", nullable = false)
    private TemplateSubtitle subtitle;
}
