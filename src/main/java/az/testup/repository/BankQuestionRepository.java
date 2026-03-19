package az.testup.repository;

import az.testup.entity.BankQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BankQuestionRepository extends JpaRepository<BankQuestion, Long> {
    List<BankQuestion> findBySubjectIdOrderByOrderIndexAscCreatedAtAsc(Long subjectId);
    long countBySubjectId(Long subjectId);

    @Query("SELECT b.topic, b.difficulty, COUNT(b) FROM BankQuestion b WHERE b.subject.id = :subjectId GROUP BY b.topic, b.difficulty")
    List<Object[]> countBySubjectGroupByTopicAndDifficulty(@Param("subjectId") Long subjectId);
}
