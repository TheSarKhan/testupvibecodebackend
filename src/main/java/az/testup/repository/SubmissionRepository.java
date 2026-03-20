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
    long countByExamIdAndSubmittedAtIsNotNullAndIsFullyGradedFalse(Long examId);
    boolean existsByExamIdAndStudentIdAndSubmittedAtIsNotNull(Long examId, Long studentId);
    long countByExamIdAndStudentIdAndSubmittedAtIsNotNull(Long examId, Long studentId);

    @Query(value = "SELECT TO_CHAR(submitted_at, 'YYYY-MM') as month, COUNT(*) as count FROM submissions WHERE submitted_at >= :since AND submitted_at IS NOT NULL GROUP BY month ORDER BY month", nativeQuery = true)
    List<Object[]> countSubmissionsByMonth(@Param("since") LocalDateTime since);
}
