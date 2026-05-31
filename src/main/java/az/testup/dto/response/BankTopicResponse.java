package az.testup.dto.response;

/**
 * A topic option for the question-bank editor. {@code recent} flags the
 * teacher's most-recently-used topics, which the client pins to the top of
 * the picker; the rest follow alphabetically.
 */
public record BankTopicResponse(String name, boolean recent) {
}
