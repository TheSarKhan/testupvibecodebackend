package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Non-null for subscription purchases */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private SubscriptionPlan plan;

    /** Non-null for exam purchases */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id")
    private Exam exam;

    @Column(nullable = false)
    private int months;

    @Column(nullable = false)
    private double amount;

    private long durationDays;

    @Column(length = 20, nullable = false)
    private String status; // PENDING, PAID, FAILED, CANCELLED

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * Whether the purchase-receipt email has been sent for this order. Guards
     * against duplicate receipts from verify retries, the KB callback, and the
     * recovery scheduler all touching the same order.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean receiptSent = false;
}
