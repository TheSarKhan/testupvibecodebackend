package az.testup.dto.response;

import lombok.Data;

/** One billing-duration option (Stripe Price) of a subscription tier. */
@Data
public class PlanPriceResponse {

    private Long id;

    /** 1/3/6/12 months. */
    private Integer durationMonths;

    /** Total amount charged for the whole period. */
    private Double price;

    private boolean visible;
}
