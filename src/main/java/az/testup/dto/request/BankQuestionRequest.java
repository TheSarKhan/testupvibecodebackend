package az.testup.dto.request;

import az.testup.enums.QuestionType;
import lombok.Data;

import java.util.List;

@Data
public class BankQuestionRequest {
    private Long subjectId;
    private String content;
    private String attachedImage;
    private QuestionType questionType;
    private Double points;
    private Integer orderIndex;
    private String correctAnswer;
    private String topic;
    private az.testup.enums.Difficulty difficulty;
    private List<BankOptionRequest> options;
    private List<BankMatchingPairRequest> matchingPairs;
}
