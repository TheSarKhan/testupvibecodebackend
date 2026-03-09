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

    /** Display order */
    private Integer orderIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;
}
