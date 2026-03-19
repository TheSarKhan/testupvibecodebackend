package az.testup.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserSubscriptionResponse {
    private Long id;
    private Long userId;
    private String userEmail;
    private SubscriptionPlanResponse plan;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private boolean isActive;
    private String paymentProvider;
    private double amountPaid;
    private int usedMonthlyExams;
    private long totalExamsCount;
}


