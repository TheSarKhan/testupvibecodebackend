-- Per-teacher reusable topics within a bank subject. Created lazily when a
-- teacher saves a question carrying a topic name; surfaced in the topic picker
-- with a "recently used" group ordered by last_used_at.
CREATE TABLE IF NOT EXISTS bank_topics (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    bank_subject_id BIGINT       NOT NULL REFERENCES bank_subjects(id) ON DELETE CASCADE,
    owner_id        BIGINT       NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    last_used_at    TIMESTAMP,
    created_at      TIMESTAMP,
    CONSTRAINT uq_bank_topics_subject_owner_name UNIQUE (bank_subject_id, owner_id, name)
);

CREATE INDEX IF NOT EXISTS idx_bank_topics_subject_owner
    ON bank_topics (bank_subject_id, owner_id, last_used_at DESC);
