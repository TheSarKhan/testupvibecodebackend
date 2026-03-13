package az.testup.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchingPairRequest {
    private Long id;

    private String leftItem;

    private String rightItem;

    private String attachedImageLeft;

    private String attachedImageRight;

    private Integer orderIndex;

}

