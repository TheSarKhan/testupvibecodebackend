-- Add gradeLevel column and tags table for bank questions
ALTER TABLE bank_questions
    ADD COLUMN IF NOT EXISTS grade_level VARCHAR(32);

-- Tags: store as a normalised list to allow filtering and aggregation later.
-- Each tag row links a single tag string to a single question.
CREATE TABLE IF NOT EXISTS bank_question_tags (
    bank_question_id BIGINT  NOT NULL REFERENCES bank_questions(id) ON DELETE CASCADE,
    tag              VARCHAR(64) NOT NULL,
    PRIMARY KEY (bank_question_id, tag)
);

CREATE INDEX IF NOT EXISTS idx_bank_questions_grade_level    ON bank_questions(grade_level);
CREATE INDEX IF NOT EXISTS idx_bank_questions_question_type  ON bank_questions(question_type);
CREATE INDEX IF NOT EXISTS idx_bank_questions_difficulty     ON bank_questions(difficulty);
CREATE INDEX IF NOT EXISTS idx_bank_questions_topic          ON bank_questions(topic);
CREATE INDEX IF NOT EXISTS idx_bank_question_tags_tag        ON bank_question_tags(tag);
