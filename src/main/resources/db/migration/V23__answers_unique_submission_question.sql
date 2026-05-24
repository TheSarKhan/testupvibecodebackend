-- Prevent duplicate Answer rows for the same (submission, question).
--
-- The save-answer endpoint reads the existing Answer from the in-memory
-- `submission.getAnswers()` collection; when two save requests for the same
-- question land concurrently, neither sees the other's just-inserted row
-- and both INSERT, producing duplicates. That later crashes the review
-- endpoint where Collectors.toMap rejects the duplicate key.
--
-- 1. Drop the older duplicate(s), keeping the highest id per
--    (submission_id, question_id). Higher id ≈ more recently saved, so the
--    student's latest content survives.
-- 2. Add a unique constraint so the second concurrent INSERT now fails fast
--    instead of silently corrupting state.

DELETE FROM answers a
USING answers b
WHERE a.submission_id = b.submission_id
  AND a.question_id = b.question_id
  AND a.id < b.id;

ALTER TABLE answers
    ADD CONSTRAINT uq_answers_submission_question UNIQUE (submission_id, question_id);
