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
    private List<ClientQuestionResponse> questions;
    private List<AnswerRequest> savedAnswers;
}
