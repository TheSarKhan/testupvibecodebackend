package az.testup.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerRequest {
    private Long questionId;
    
    // For single/multiple choice 
    private List<Long> optionIds;
    
    // For open-ended
    private String textAnswer;

    // For open-ended image upload (base64)
    private String answerImage;

    // For matching
    private List<MatchingPairAnswerRequest> matchingPairs;
}
