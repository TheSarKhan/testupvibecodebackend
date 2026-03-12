package az.testup.dto.response;

public record BankMatchingPairResponse(
        Long id,
        String leftItem,
        String rightItem,
        Integer orderIndex
) {}
