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

    // Stable per-node identity from the editor; preserves distinct items that
    // share content and keeps image-only items from collapsing.
    private String leftVisualId;

    private String rightVisualId;

    private Integer orderIndex;

}

