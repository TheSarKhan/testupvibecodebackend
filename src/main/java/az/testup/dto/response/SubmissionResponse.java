package az.testup.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
