package az.testup.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SubjectCategoryResponse(
        Long id,
        String name,
        Integer orderIndex,
        String color,
        @JsonProperty("default") boolean isDefault
) {}
