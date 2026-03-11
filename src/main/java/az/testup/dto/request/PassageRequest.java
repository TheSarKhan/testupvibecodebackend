package az.testup.dto.request;

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
public class PassageRequest {
    private Long id;

    private PassageType passageType;

    private String title;

    private String textContent;

    private String attachedImage;

    private String audioContent;

    /** null = unlimited listens */
    private Integer listenLimit;

    private Integer orderIndex;

    private String subjectGroup;

    private List<QuestionRequest> questions;
}
