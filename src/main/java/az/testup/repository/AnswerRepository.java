package az.testup.repository;

import az.testup.entity.Answer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Atomic save of a student's in-progress answer. Insert the row, or — if one
     * already exists for this (submission, question) — overwrite the student-input
     * columns. The whole thing is one statement guarded by the
     * uq_answers_submission_question constraint, so two concurrent auto-saves of
     * the same question can never produce a duplicate row or a failed INSERT that
     * poisons the Hibernate session (the old REQUIRES_NEW + catch dance was
     * isolation/proxy dependent and still surfaced 500s). Grading columns
     * (score, is_graded, question_snapshot, feedback) are intentionally left
     * untouched on conflict — they are set only at submit/grade time; a re-saved
     * answer is reset to ungraded so a later grade re-runs cleanly.
     */
    @Modifying
    @Query(value = """
            INSERT INTO answers (submission_id, question_id, answer_text, selected_option_id,
                                 matching_answer_json, selected_option_ids_json, answer_image, is_graded)
            VALUES (:submissionId, :questionId, :answerText, :selectedOptionId,
                    :matchingAnswerJson, :selectedOptionIdsJson, :answerImage, false)
            ON CONFLICT (submission_id, question_id) DO UPDATE SET
                answer_text = EXCLUDED.answer_text,
                selected_option_id = EXCLUDED.selected_option_id,
                matching_answer_json = EXCLUDED.matching_answer_json,
                selected_option_ids_json = EXCLUDED.selected_option_ids_json,
                answer_image = EXCLUDED.answer_image,
                is_graded = false
            """, nativeQuery = true)
    void upsertAnswer(@Param("submissionId") Long submissionId,
                      @Param("questionId") Long questionId,
                      @Param("answerText") String answerText,
                      @Param("selectedOptionId") Long selectedOptionId,
                      @Param("matchingAnswerJson") String matchingAnswerJson,
                      @Param("selectedOptionIdsJson") String selectedOptionIdsJson,
                      @Param("answerImage") String answerImage);

    /** Bulk-remove all answers of a submission (used at submit before re-inserting the final set). */
    @Modifying
    @Query("DELETE FROM Answer a WHERE a.submission.id = :submissionId")
    void deleteBySubmissionId(@Param("submissionId") Long submissionId);
}
