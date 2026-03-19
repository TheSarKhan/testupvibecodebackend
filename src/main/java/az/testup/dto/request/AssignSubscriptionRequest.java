package az.testup.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssignSubscriptionRequest {

    @NotNull(message = "User ID is required")
    private Long userId;

    @NotNull(message = "Plan ID is required")
    private Long planId;

    @NotNull(message = "Duration in months is required")
    private Integer durationMonths;

    private String paymentProvider; // e.g. "MANUAL", "EPOINT"
    private String transactionId;
    private Double amountPaid; // economic value (credit + cash) stored on subscription
    private Long durationDays; // explicit subscription duration in days (takes priority over durationMonths)
}
