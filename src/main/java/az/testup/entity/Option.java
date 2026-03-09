package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "options")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Option {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Option text */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Whether this option is the correct answer */
    @Column(nullable = false)
    private Boolean isCorrect;

    /** Display order */
    private Integer orderIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;
}
