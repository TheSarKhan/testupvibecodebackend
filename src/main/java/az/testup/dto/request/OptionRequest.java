package az.testup.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionRequest {
    @NotBlank(message = "Content is required")
    private String content;

    @NotNull(message = "isCorrect is required")
    private Boolean isCorrect;

    private Integer orderIndex;
}
