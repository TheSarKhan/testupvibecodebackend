package az.testup.repository;

import az.testup.entity.StudentSavedExam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudentSavedExamRepository extends JpaRepository<StudentSavedExam, Long> {
    List<StudentSavedExam> findByStudentIdOrderBySavedAtDesc(Long studentId);
    Optional<StudentSavedExam> findByStudentIdAndExamId(Long studentId, Long examId);
    boolean existsByStudentIdAndExamId(Long studentId, Long examId);
    void deleteByStudentIdAndExamId(Long studentId, Long examId);
}
