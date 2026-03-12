package az.testup.dto.request;

import lombok.Data;

@Data
public class BankMatchingPairRequest {
    private Long id;
    private String leftItem;
    private String rightItem;
    private Integer orderIndex;
}
