package az.testup.dto.response;

import az.testup.enums.QuestionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionReviewResponse {
    private Long id;
    private String content;
    private String attachedImage;
    private QuestionType questionType;
    private Double points;
    private Integer orderIndex;
    
    // Student's data
    private String studentAnswerText;
    private Long studentSelectedOptionId;
    private String studentMatchingAnswerJson;
    private Double awardedScore;
    private Boolean isGraded;
    private String feedback;

    // Correct data
    private String correctAnswer; // For open questions
    private List<OptionReviewResponse> options;
    private List<ClientMatchingPairResponse> matchingPairs;
}
