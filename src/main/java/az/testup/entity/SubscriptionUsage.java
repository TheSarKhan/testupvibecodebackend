package az.testup.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "subscription_usages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_subscription_id", nullable = false)
    private UserSubscription userSubscription;

    // To track which month this usage corresponds to (e.g. "2026-03")
    @Column(nullable = false, length = 7)
    private String monthYear; 

    @Builder.Default
    @Column(nullable = false)
    private int usedMonthlyExams = 0;

    // Saved exams might not be strictly monthly, but we can track the current total here
    // Or we can dynamically compute total saved exams from DB, but caching it here is also an option.
    // Let's keep a tracker for it anyway.
    @Builder.Default
    @Column(nullable = false)
    private int usedSavedExams = 0;

    @Builder.Default
    @Column(nullable = false)
    private int usedAiQuestions = 0;
}
