package az.testup.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchingPairRequest {
    @NotBlank(message = "Left item is required")
    private String leftItem;

    @NotBlank(message = "Right item is required")
    private String rightItem;

    private Integer orderIndex;
}
