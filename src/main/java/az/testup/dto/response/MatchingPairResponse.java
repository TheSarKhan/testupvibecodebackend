package az.testup.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchingPairResponse {
    private Long id;
    private String leftItem;
    private String rightItem;
    // Per-side image attachments — were missing from this DTO, so a picture-based
    // matching pair (no text) showed up blank in the editor and in the
    // collaborative review screen. Carry them through.
    private String attachedImageLeft;
    private String attachedImageRight;
    // Stable per-node identity — lets the editor keep distinct items that share
    // text/image (and image-only items) from collapsing into one node.
    private String leftVisualId;
    private String rightVisualId;
    private Integer orderIndex;
}
