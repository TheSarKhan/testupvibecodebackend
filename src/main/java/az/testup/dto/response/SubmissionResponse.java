package az.testup.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
    private Instant startedAt;
    private Instant submittedAt;
    private Integer durationMinutes;
    /** Whether the student paid for this exam (null if exam is free) */
    private Boolean hasPaid;
    /** Amount the student paid (null if exam is free) */
    private BigDecimal amountPaid;

    /** Formula-based percentage score for template exams (null for non-template exams) */
    private Double templateScorePercent;

    /** Answer breakdown counts for result chart */
    private Integer correctCount;
    private Integer wrongCount;
    private Integer skippedCount;
    private Integer pendingManualCount;

    /** Extra exam details for student dashboard */
    private List<String> subjects;
    private List<String> tags;
    private String examType;
    private Integer questionCount;
    private String teacherName;

    /** Per-subject breakdown (only populated when exam has 2+ subjects) */
    private List<SubjectStatResponse> subjectStats;
}
