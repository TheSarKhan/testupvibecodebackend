package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Column(nullable = false)
    private LocalDateTime startDate;

    @Column(nullable = false)
    private LocalDateTime endDate;

    @Column(nullable = false)
    private boolean isActive;

    @Column(length = 100)
    private String paymentProvider;

    @Column(length = 100)
    private String transactionId;

    @Column(nullable = false)
    private double amountPaid = 0.0;

    /**
     * Billing duration (in months) of the price this subscription was bought at.
     * Stored so proration can recompute the daily rate (amountPaid / durationMonths*30)
     * without depending on a {@code SubscriptionPlanPrice} row still existing/visible.
     * Nullable for legacy/admin-assigned rows created before this column existed.
     */
    @Column(name = "duration_months")
    private Integer durationMonths;
}
