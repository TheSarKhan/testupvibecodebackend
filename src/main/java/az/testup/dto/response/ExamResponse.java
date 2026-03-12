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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamResponse {
    private Long id;
    private String title;
    private String description;
    private List<String> subjects;
    private ExamVisibility visibility;
    private ExamType examType;
    private ExamStatus status;
    private String accessCode;
    private LocalDateTime accessCodeExpiresAt;
    private String shareLink;
    private Integer durationMinutes;
    private Long teacherId;
    private String teacherName;
    private BigDecimal price;
    private boolean sitePublished;
    private Long templateId;
    private Long templateSectionId;
    private List<String> tags;
    private List<QuestionResponse> questions;
    private List<PassageResponse> passages;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
