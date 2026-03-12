package az.testup.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionReviewResponse {
    private Long id;
    private Long examId;
    private String examTitle;
    private Double totalScore;
    private Double maxScore;
    private LocalDateTime startedAt;
    private LocalDateTime submittedAt;
    private Boolean isFullyGraded;
    private Integer ungradedCount;
    private Integer rating;
    private List<QuestionReviewResponse> questions;
    private Double templateScorePercent;
}
