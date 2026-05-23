package az.testup.repository;

import az.testup.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    List<Question> findByExamIdOrderByOrderIndex(Long examId);

    /**
     * Batched count of questions per exam — used by list endpoints so we don't
     * hit lazy {@code Exam.getQuestions()} N times. Each row is [examId, count].
     * Question already has exam_id set even when it belongs to a passage, so
     * this returns the total (standalone + passage) count.
     */
    @Query("SELECT q.exam.id, COUNT(q.id) FROM Question q WHERE q.exam.id IN :examIds GROUP BY q.exam.id")
    List<Object[]> countByExamIdIn(@Param("examIds") List<Long> examIds);
}
