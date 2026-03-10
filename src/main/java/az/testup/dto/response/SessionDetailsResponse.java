package az.testup.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import az.testup.dto.request.AnswerRequest;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionDetailsResponse {
    private Long submissionId;
    private String examTitle;
    private Integer durationMinutes;
    private LocalDateTime startedAt;
    /** Standalone questions (no passage) */
    private List<ClientQuestionResponse> questions;
    /** Passage groups with their questions embedded */
    private List<ClientPassageResponse> passages;
    private List<AnswerRequest> savedAnswers;
}
