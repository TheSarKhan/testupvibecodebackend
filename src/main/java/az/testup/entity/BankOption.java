package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "bank_options")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isCorrect = false;

    private Integer orderIndex;

    @Column(columnDefinition = "TEXT")
    private String attachedImage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_question_id", nullable = false)
    private BankQuestion question;
}
