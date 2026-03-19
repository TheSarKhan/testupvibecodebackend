package az.testup.dto.request;

import az.testup.enums.QuestionType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionRequest {
    private Long id;

    @NotBlank(message = "Content is required")
    private String content;

    /** Compatibility for frontend which sometimes sends 'text' */
    private String text;


    private String attachedImage;

    @NotNull(message = "Question type is required")
    private QuestionType questionType;

    @NotNull(message = "Points are required")
    @DecimalMin(value = "0.5", message = "Bal minimum 0.5 olmalıdır")
    @DecimalMax(value = "100.0", message = "Bal maksimum 100 ola bilər")
    private Double points;

    private Integer orderIndex;

    private String correctAnswer;

    private String subjectGroup;

    private List<OptionRequest> options;

    private List<MatchingPairRequest> matchingPairs;
}

