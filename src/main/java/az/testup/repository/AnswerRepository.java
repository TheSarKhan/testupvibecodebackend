package az.testup.repository;

import az.testup.entity.Answer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AnswerRepository extends JpaRepository<Answer, Long> {
    /**
     * Direct DB lookup — used by saveAnswer to avoid the race where two
     * concurrent saves each see an empty in-memory collection on their own
     * transaction snapshot and both INSERT a fresh row.
     */
    Optional<Answer> findBySubmissionIdAndQuestionId(Long submissionId, Long questionId);
}
