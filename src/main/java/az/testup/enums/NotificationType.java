package az.testup.enums;

public enum NotificationType {
    SYSTEM,           // General system messages
    EXAM_CREATED,     // When an exam is created
    EXAM_GRADED,      // When an exam results are ready
    ANNOUNCEMENT,     // Announcements from admin
    PAYMENT_SUCCESS,  // Subscription/Exam purchase success
    WARNING           // Warnings
}
