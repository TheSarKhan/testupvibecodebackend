package az.testup.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubjectStatResponse {
    private String subjectName;
    private int questionCount;
    private int correctCount;
    private int wrongCount;
    private int skippedCount;
    private int pendingManualCount;
    private double totalScore;
    private double maxScore;

    /** Formula-based percentage for this subject (only set in multi-section template exams) */
    private Double formulaPercent;

    /** Actual score earned in this section = formulaPercent/100 * sectionMaxScore (null if maxScore not set) */
    private Double sectionScore;

    /** Maximum achievable score for this section (from TemplateSection.maxScore) */
    private Double sectionMaxScore;
}
