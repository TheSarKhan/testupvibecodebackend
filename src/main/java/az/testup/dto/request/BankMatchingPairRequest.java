package az.testup.dto.request;

import lombok.Data;

@Data
public class BankMatchingPairRequest {
    private Long id;
    private String leftItem;
    private String rightItem;
    private String attachedImageLeft;
    private String attachedImageRight;
    private String leftVisualId;
    private String rightVisualId;
    private Integer orderIndex;
}
