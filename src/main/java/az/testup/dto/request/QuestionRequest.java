package az.testup.dto.request;

import az.testup.enums.QuestionType;
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

    private String attachedImage;

    @NotNull(message = "Question type is required")
    private QuestionType questionType;

    @NotNull(message = "Points are required")
    private Double points;

    private Integer orderIndex;

    private String correctAnswer;

    private List<OptionRequest> options;

    private List<MatchingPairRequest> matchingPairs;
}

