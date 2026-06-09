package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * One billing-duration price for a {@link SubscriptionPlan} tier (Stripe-style
 * Price). The tier ({@code plan}) carries the features/limits; this row carries
 * "how you pay" — the duration and the total amount for that period. A tier has
 * one row per supported duration (1/3/6/12 months), so a 3-month Basic and a
 * 12-month Basic are two prices on the same Basic tier rather than two plans.
 */
@Entity
@Table(name = "subscription_plan_prices",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_plan_price_plan_duration",
                columnNames = {"plan_id", "duration_months"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionPlanPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    /** Billing duration: 1/3/6/12 are the storefront-supported values. */
    @Column(name = "duration_months", nullable = false)
    private Integer durationMonths;

    /** Total amount charged for the whole period (NOT a per-month rate). */
    @Column(nullable = false)
    private Double price;

    /**
     * Admin-controlled visibility for this specific duration. Lets us pull a
     * single SKU (e.g. hide the 12-month option) without touching the tier.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean visible = true;
}
