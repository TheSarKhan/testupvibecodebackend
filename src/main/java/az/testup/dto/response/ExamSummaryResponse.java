package az.testup.dto.response;

import az.testup.enums.ExamStatus;
import az.testup.enums.ExamType;
import az.testup.enums.ExamVisibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lightweight projection used by list pages (Teacher MyExams, Profile teacher
 * tab). The full {@link ExamResponse} eagerly inflates every question, option
 * and matching pair which makes the list endpoint do ~1k DB queries per call;
 * the list UI only needs counts and top-level metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamSummaryResponse {
    private Long id;
    private String title;
    private List<String> subjects;
    private ExamVisibility visibility;
    private ExamType examType;
    private ExamStatus status;
    private String shareLink;
    private Integer durationMinutes;
    private Long teacherId;
    private BigDecimal price;
    private boolean sitePublished;
    private List<String> tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private boolean isCollaborative;
    private Long collaborativeParentId;

    // Aggregates — backed by single GROUP BY queries instead of one-per-exam
    private Integer questionCount;
    private Long participantCount;
    private Long pendingManualCount;
    private Double averageRating;
    private Long ratingCount;
}
