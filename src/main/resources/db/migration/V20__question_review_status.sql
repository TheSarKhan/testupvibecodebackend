-- Per-question review state for collaborative exam drafts.
-- review_status is NULL for non-collaborative questions; for draft questions
-- in a collaborative exam it cycles PENDING -> APPROVED / REJECTED.
ALTER TABLE questions
    ADD COLUMN review_status   VARCHAR(20),
    ADD COLUMN review_comment  TEXT;

CREATE INDEX idx_questions_review_status ON questions(review_status);
