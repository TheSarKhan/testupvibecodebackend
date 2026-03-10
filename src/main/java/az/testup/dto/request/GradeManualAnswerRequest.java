package az.testup.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GradeManualAnswerRequest {
    /** The question ID to grade */
    private Long questionId;

    /**
     * Fraction of the question's points to award:
     * 0.0 = 0 bal, 0.3333... = 1/3 bal, 0.6666... = 2/3 bal, 1.0 = tam bal
     */
    private Double fraction;

    /** Optional teacher feedback text */
    private String feedback;
}
