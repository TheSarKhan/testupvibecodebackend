package az.testup.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientMatchingPairResponse {
    private Long id;
    private String leftItem;
    private String attachedImageLeft;
    private String rightItem;
    private String attachedImageRight;
    // Stable per-node identity so the exam/review screens dedupe by node, not
    // by content — distinct items with the same text/image stay separate.
    private String leftVisualId;
    private String rightVisualId;
}
