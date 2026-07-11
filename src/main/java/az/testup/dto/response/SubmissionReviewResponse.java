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

    /** Test taker — student account when registered, guest name otherwise. */
    private Long studentId;
    private String studentName;
    private String studentEmail;
    /** True when the submission came from a guest (no registered account). */
    private Boolean isGuest;
    private Double totalScore;
    private Double maxScore;
    private Instant startedAt;
    private Instant submittedAt;
    private Boolean isFullyGraded;
    private Integer ungradedCount;
    private Integer rating;
    private List<QuestionReviewResponse> questions;
    /**
     * Passage groups (TEXT / LISTENING) with their content. Questions in the
     * flat `questions` list reference these by `passageId`; the review UI groups
     * passage questions under the matching passage's content.
     */
    private List<ClientPassageResponse> passages;
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
