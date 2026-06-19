-- V33: soft-delete flag for users.
-- Deleting a teacher who authored exams must NOT cascade-delete those exams,
-- because every student's submission (their result) hangs off the exam. Instead
-- such an account is anonymized + disabled and flagged deleted = true, so the
-- exams and all student results stay intact while the account drops off the
-- admin list and can no longer log in. Accounts with no authored content are
-- still hard-deleted (row removed) and never carry this flag.
-- Idempotent: re-runs are harmless.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;
