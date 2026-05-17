package az.testup.dto.response;

public record BankSubjectResponse(
        Long id,
        String name,
        Long ownerId,
        String ownerName,
        Boolean isGlobal,
        int questionCount,
        String createdAt,
        // Enriched fields (nullable) — populated by /bank/subjects only
        String iconEmoji,
        String color,
        Integer easyCount,
        Integer mediumCount,
        Integer hardCount,
        Integer topicCount,
        String lastAddedAt
) {
    public BankSubjectResponse(Long id, String name, Long ownerId, String ownerName,
                               Boolean isGlobal, int questionCount, String createdAt) {
        this(id, name, ownerId, ownerName, isGlobal, questionCount, createdAt,
             null, null, null, null, null, null, null);
    }
}
