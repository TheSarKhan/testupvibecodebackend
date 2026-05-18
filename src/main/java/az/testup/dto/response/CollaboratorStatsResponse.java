package az.testup.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Statistics for one collaborator's slice of a collaborative exam — i.e. only the
 * questions the teacher is responsible for. Returned by
 * GET /api/collaborative-exams/collaborator/{id}/stats.
 */
public record CollaboratorStatsResponse(
        Long collaboratorId,
        Long examId,
        String examTitle,
        Long teacherId,
        String teacherName,
        List<String> subjects,
        int questionCount,
        double totalPoints,
        int studentCount,
        int pendingManualCount,
        Double avgScore,
        Double avgPercent,
        List<CollaboratorStatStudent> students,
        List<CollaboratorStatQuestion> questions
) {

    public record CollaboratorStatStudent(
            Long submissionId,
            Long studentId,
            String studentName,
            Double score,
            Double maxScore,
            Double percent,
            int correctCount,
            int partialCount,
            int wrongCount,
            int skippedCount,
            int pendingManualCount,
            Instant submittedAt
    ) {}

    public record CollaboratorStatQuestion(
            Long questionId,
            String content,
            String questionType,
            String subjectGroup,
            Double points,
            int attemptCount,
            int correctCount,
            int partialCount,
            int wrongCount,
            int skippedCount,
            int pendingManualCount,
            Double avgScore,
            Double correctRate
    ) {}
}
