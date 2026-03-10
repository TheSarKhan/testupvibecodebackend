package az.testup.dto.response;

import az.testup.enums.PassageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PassageResponse {
    private Long id;
    private PassageType passageType;
    private String title;
    private String textContent;
    private String attachedImage;
    private String audioContent;
    private Integer listenLimit;
    private Integer orderIndex;
    private List<QuestionResponse> questions;
}
