package az.testup.dto.response;

public record BankSubjectResponse(
        Long id,
        String name,
        Long ownerId,
        String ownerName,
        Boolean isGlobal,
        int questionCount,
        String createdAt
) {}
