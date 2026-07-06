package az.testup.dto.response;

public record BankMatchingPairResponse(
        Long id,
        String leftItem,
        String rightItem,
        String attachedImageLeft,
        String attachedImageRight,
        String leftVisualId,
        String rightVisualId,
        Integer orderIndex
) {}
