package az.testup.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionResponse {
    private Long id;
    private Long examId;
    private String examTitle;
    private String studentName;
    private Double totalScore;
    private Double maxScore;
    private Boolean isFullyGraded;
    private Integer rating;
    private LocalDateTime startedAt;
    private LocalDateTime submittedAt;
    private Integer durationMinutes;
    /** Whether the student paid for this exam (null if exam is free) */
    private Boolean hasPaid;
    /** Amount the student paid (null if exam is free) */
    private BigDecimal amountPaid;
}
