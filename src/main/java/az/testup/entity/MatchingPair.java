package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "matching_pairs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchingPair {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Left side item */
    @Column(columnDefinition = "TEXT")
    private String leftItem;

    /** Right side item (correct match) */
    @Column(columnDefinition = "TEXT")
    private String rightItem;

    @Column(columnDefinition = "TEXT")
    private String attachedImageLeft;

    @Column(columnDefinition = "TEXT")
    private String attachedImageRight;

    /**
     * Stable per-node identity supplied by the editor. Many rows can share the
     * same leftVisualId (one left node linked to several rights), while two
     * distinct nodes with identical text/image stay separate. Nullable: legacy
     * rows fall back to content-based grouping on the frontend.
     */
    @Column(name = "left_visual_id", length = 64)
    private String leftVisualId;

    @Column(name = "right_visual_id", length = 64)
    private String rightVisualId;

    /** Display order */
    private Integer orderIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;
}
