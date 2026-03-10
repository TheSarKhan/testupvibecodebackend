package az.testup.entity;

import az.testup.enums.PassageType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "passages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Passage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PassageType passageType;

    private String title;

    /** Rich text/LaTeX content for TEXT type */
    @Column(columnDefinition = "TEXT")
    private String textContent;

    /** Optional image for TEXT type (base64) */
    @Column(columnDefinition = "TEXT")
    private String attachedImage;

    /** Base64-encoded audio for LISTENING type */
    @Column(columnDefinition = "TEXT")
    private String audioContent;

    /** Max listen count; null = unlimited */
    private Integer listenLimit;

    /** Display order within the exam (interleaved with standalone questions) */
    private Integer orderIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;
}
