package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "bank_matching_pairs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankMatchingPair {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String leftItem;

    @Column(columnDefinition = "TEXT")
    private String rightItem;

    @Column(name = "attached_image_left", columnDefinition = "TEXT")
    private String attachedImageLeft;

    @Column(name = "attached_image_right", columnDefinition = "TEXT")
    private String attachedImageRight;

    // Stable per-node identity — see MatchingPair. Nullable for legacy rows.
    @Column(name = "left_visual_id", length = 64)
    private String leftVisualId;

    @Column(name = "right_visual_id", length = 64)
    private String rightVisualId;

    private Integer orderIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_question_id", nullable = false)
    private BankQuestion question;
}
