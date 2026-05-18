package az.testup.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionReviewResponse {
    private Long id;
    private Long examId;
    private String examTitle;
    private String examSubject;
    private Double totalScore;
    private Double maxScore;
    private Instant startedAt;
    private Instant submittedAt;
    private Boolean isFullyGraded;
    private Integer ungradedCount;
    private Integer rating;
    private List<QuestionReviewResponse> questions;
    private Double templateScorePercent;
    private Double templateTotalScore;
    private Double templateTotalMaxScore;
    /**
     * Question IDs the current viewer is allowed to manually grade. Admin / parent exam
     * owner sees the full set; a collaborative-exam section teacher only sees the ids of
     * questions whose subjectGroup falls inside their assignment. The frontend hides the
     * grading panel for any OPEN_MANUAL question whose id is not in this set, so section
     * teachers never see a "grade" button they would just hit a 401 on.
     */
    private Set<Long> gradableQuestionIds;
}
