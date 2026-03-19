package az.testup.dto.response;

public record BankOptionResponse(
        Long id,
        String content,
        Boolean isCorrect,
        Integer orderIndex,
        String attachedImage
) {}
