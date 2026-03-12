package az.testup.dto.request;

import az.testup.enums.ExamStatus;
import az.testup.enums.ExamType;
import az.testup.enums.ExamVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamRequest {
    @NotBlank(message = "Title is required")
    private String title;

    private String description;

    private List<String> subjects;

    @NotNull(message = "Visibility is required")
    private ExamVisibility visibility;

    @NotNull(message = "Exam type is required")
    private ExamType examType;

    @NotNull(message = "Status is required")
    private ExamStatus status;

    private Integer durationMinutes;

    private Long templateId;

    private Long templateSectionId;

    private List<String> tags;

    /** Standalone questions (not belonging to any passage) */
    private List<QuestionRequest> questions;

    /** Passage groups (TEXT or LISTENING), each containing their own questions */
    private List<PassageRequest> passages;
}
