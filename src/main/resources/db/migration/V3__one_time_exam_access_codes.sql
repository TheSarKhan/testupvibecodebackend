-- Replace shared exam access code with per-student one-time-use codes

-- Drop old shared code columns from exams table
ALTER TABLE exams
    DROP COLUMN IF EXISTS access_code,
    DROP COLUMN IF EXISTS access_code_expires_at;

-- New table: one-time-use access codes for PRIVATE exams
CREATE TABLE exam_access_codes (
    id          BIGSERIAL PRIMARY KEY,
    exam_id     BIGINT       NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    code        VARCHAR(10)  NOT NULL UNIQUE,
    expires_at  TIMESTAMP    NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_exam_access_codes_code ON exam_access_codes(code);
