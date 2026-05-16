package az.testup.dto.response;

public record TagResponse(
        Long id,
        String name,
        String color,
        long usageCount
) {}
