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
public class ClientQuestionResponse {
    private Long id;
    private String content;
    private String attachedImage;
    private QuestionType questionType;
    private Double points;
    private Integer orderIndex;
    private List<ClientOptionResponse> options;
    private List<ClientMatchingPairResponse> matchingPairs;
}
