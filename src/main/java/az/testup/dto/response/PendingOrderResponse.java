package az.testup.dto.response;

public record PendingOrderResponse(
        Long id,
        String orderId,
        String userEmail,
        String userName,
        String planName,
        double amount,
        long durationDays,
        int months,
        String createdAt,
        boolean isExamOrder
) {}
