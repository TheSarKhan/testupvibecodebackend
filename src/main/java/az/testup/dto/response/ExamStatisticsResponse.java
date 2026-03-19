package az.testup.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamStatisticsResponse {
    private Long examId;
    private String examTitle;
    private BigDecimal examPrice;
    private Integer totalParticipants;
    private Double averageScore;
    private Double maximumScore;
    private Double averageRating;
    private Integer averageDurationMinutes;
    private List<TopStudentDTO> topStudents;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopStudentDTO {
        private String name;
        private Double score;
        private String timeSpent;
    }
}
