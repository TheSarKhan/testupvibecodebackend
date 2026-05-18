-- Phase 4: track the parent-exam copy of an approved collaborative-draft question so
-- re-approval after a re-edit overwrites the existing copy instead of duplicating it.
-- NULL for non-collab questions and for never-approved draft questions.
ALTER TABLE questions
    ADD COLUMN parent_copy_id BIGINT;

CREATE INDEX idx_questions_parent_copy_id ON questions(parent_copy_id);
