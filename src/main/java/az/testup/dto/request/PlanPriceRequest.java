package az.testup.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** One billing-duration option (Stripe Price) when creating/updating a tier. */
@Data
public class PlanPriceRequest {

    @NotNull(message = "durationMonths is required")
    @Min(value = 1, message = "durationMonths must be >= 1")
    private Integer durationMonths;

    @NotNull(message = "Price is required")
    @Min(value = 0, message = "Price cannot be negative")
    private Double price;

    /** Defaults to true so prices created without this field stay visible. */
    private boolean visible = true;
}
