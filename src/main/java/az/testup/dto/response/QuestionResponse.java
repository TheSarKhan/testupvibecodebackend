package az.testup.dto.response;

import az.testup.enums.QuestionReviewStatus;
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
public class QuestionResponse {
    private Long id;
    private String content;
    private String attachedImage;
    private QuestionType questionType;
    private Double points;
    private Integer orderIndex;
    private String correctAnswer;
    private String subjectGroup;
    private List<OptionResponse> options;
    private List<MatchingPairResponse> matchingPairs;
    /** Per-question review state inside a collaborative draft (null otherwise). */
    private QuestionReviewStatus reviewStatus;
    /** Admin's per-question comment when reviewStatus = REJECTED. */
    private String reviewComment;
}
