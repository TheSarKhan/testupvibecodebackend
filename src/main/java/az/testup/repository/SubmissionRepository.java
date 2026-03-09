package az.testup.repository;

import az.testup.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    List<Submission> findByExamId(Long examId);
    List<Submission> findByStudentId(Long studentId);
    List<Submission> findByStudentIdAndSubmittedAtIsNull(Long studentId);
    Optional<Submission> findByStudentIdAndExamIdAndSubmittedAtIsNull(Long studentId, Long examId);
}
