package az.testup.repository;

import az.testup.entity.ExamCollaborator;
import az.testup.enums.CollaboratorStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamCollaboratorRepository extends JpaRepository<ExamCollaborator, Long> {

    List<ExamCollaborator> findByTeacherId(Long teacherId);

    List<ExamCollaborator> findByCollaborativeExamId(Long examId);

    Optional<ExamCollaborator> findByCollaborativeExamIdAndTeacherId(Long examId, Long teacherId);

    List<ExamCollaborator> findByCollaborativeExamIdAndStatus(Long examId, CollaboratorStatus status);

    Optional<ExamCollaborator> findByDraftExamId(Long draftExamId);

    long countByStatus(CollaboratorStatus status);
}
