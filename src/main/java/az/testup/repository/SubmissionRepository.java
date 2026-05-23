package az.testup.repository;

import az.testup.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    List<Submission> findByExamId(Long examId);
    void deleteByExamId(Long examId);
    List<Submission> findByStudentId(Long studentId);
    List<Submission> findByStudentIdAndSubmittedAtIsNull(Long studentId);
    Optional<Submission> findByStudentIdAndExamIdAndSubmittedAtIsNull(Long studentId, Long examId);
    List<Submission> findByExamIdInAndSubmittedAtIsNotNullAndIsFullyGradedFalse(List<Long> examIds);
    long countByExamIdAndSubmittedAtIsNotNull(Long examId);
    long countByExamIdAndSubmittedAtIsNotNullAndIsFullyGradedFalse(Long examId);

    @Query("SELECT COUNT(s) FROM Submission s WHERE s.exam.id = :examId " +
           "AND s.submittedAt IS NOT NULL AND s.isFullyGraded = false " +
           "AND (s.hiddenFromTeacher IS NULL OR s.hiddenFromTeacher = false)")
    long countPendingGradingByExamIdExcludingHidden(@Param("examId") Long examId);
    boolean existsByExamIdAndStudentIdAndSubmittedAtIsNotNull(Long examId, Long studentId);
    long countByExamIdAndStudentIdAndSubmittedAtIsNotNull(Long examId, Long studentId);

    @Query(value = "SELECT TO_CHAR(submitted_at, 'YYYY-MM') as month, COUNT(*) as count FROM submissions WHERE submitted_at >= :since AND submitted_at IS NOT NULL GROUP BY month ORDER BY month", nativeQuery = true)
    List<Object[]> countSubmissionsByMonth(@Param("since") LocalDateTime since);

    @Query("SELECT s FROM Submission s WHERE s.submittedAt IS NULL AND s.startedAt IS NOT NULL AND s.exam.durationMinutes IS NOT NULL AND s.exam.durationMinutes > 0")
    List<Submission> findActiveTimedSubmissions();

    @Query("SELECT AVG(s.rating) FROM Submission s WHERE s.exam.id = :examId AND s.rating IS NOT NULL")
    Double findAverageRatingByExamId(@Param("examId") Long examId);

    @Query("SELECT COUNT(s) FROM Submission s WHERE s.exam.id = :examId AND s.rating IS NOT NULL")
    long countRatingsByExamId(@Param("examId") Long examId);

    // ── Batched aggregates for list endpoints (avoid N+1) ───────────────────
    // Each method returns [examId, value] rows so the caller can build a
    // Map<Long, Long/Double> instead of issuing one query per exam.

    @Query("SELECT s.exam.id, COUNT(s) FROM Submission s " +
           "WHERE s.exam.id IN :examIds AND s.submittedAt IS NOT NULL AND s.isFullyGraded = false " +
           "AND (s.hiddenFromTeacher IS NULL OR s.hiddenFromTeacher = false) " +
           "GROUP BY s.exam.id")
    List<Object[]> countPendingGradingByExamIdIn(@Param("examIds") List<Long> examIds);

    @Query("SELECT s.exam.id, COUNT(s) FROM Submission s " +
           "WHERE s.exam.id IN :examIds AND s.submittedAt IS NOT NULL " +
           "GROUP BY s.exam.id")
    List<Object[]> countParticipantsByExamIdIn(@Param("examIds") List<Long> examIds);

    @Query("SELECT s.exam.id, AVG(s.rating), COUNT(s.rating) FROM Submission s " +
           "WHERE s.exam.id IN :examIds AND s.rating IS NOT NULL " +
           "GROUP BY s.exam.id")
    List<Object[]> findRatingStatsByExamIdIn(@Param("examIds") List<Long> examIds);
}
