package az.testup.enums;

/**
 * Per-question review lifecycle inside a collaborative exam draft.
 * Null = not part of a review (e.g. parent exam questions, standalone exams).
 */
public enum QuestionReviewStatus {
    PENDING,   // Müəllim göndərib, admin hələ baxmayıb
    APPROVED,  // Admin təsdiqlədi, sual əsas imtahana köçürüldü
    REJECTED   // Admin rədd etdi, müəllimə geri qayıdır
}
