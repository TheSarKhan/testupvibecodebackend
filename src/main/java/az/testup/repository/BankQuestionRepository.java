package az.testup.repository;

import az.testup.entity.BankQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankQuestionRepository extends JpaRepository<BankQuestion, Long> {
    List<BankQuestion> findBySubjectIdOrderByOrderIndexAscCreatedAtAsc(Long subjectId);
    long countBySubjectId(Long subjectId);
}
