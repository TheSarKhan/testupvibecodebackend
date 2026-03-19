package az.testup.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionReviewResponse {
    private Long id;
    private String content;
    private Boolean isCorrect;
    private Integer orderIndex;
    private String attachedImage;
}
