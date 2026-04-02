package az.testup.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    @JsonProperty("isActive")
    private boolean isActive;
    private String paymentProvider;
    private double amountPaid;
    private int usedMonthlyExams;
    private long totalExamsCount;
}


