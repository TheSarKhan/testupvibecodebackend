package az.testup.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ExamSubjectResponse(
        Long id,
        String name,
        String color,
        String iconEmoji,
        String description,
        @JsonProperty("default") boolean isDefault,
        List<SubjectTopicResponse> topics
) {}
