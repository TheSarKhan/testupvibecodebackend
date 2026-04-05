-- Fix timezone issues by storing all timestamps in UTC as Instant
-- Convert TIMESTAMP columns to BIGINT to store as milliseconds since epoch (timezone-independent)

-- For submissions table
ALTER TABLE submissions
  ALTER COLUMN started_at TYPE BIGINT USING EXTRACT(EPOCH FROM started_at) * 1000,
  ALTER COLUMN submitted_at TYPE BIGINT USING EXTRACT(EPOCH FROM submitted_at) * 1000,
  ALTER COLUMN created_at TYPE BIGINT USING EXTRACT(EPOCH FROM created_at) * 1000;

-- For exam_access_codes table
ALTER TABLE exam_access_codes
  ALTER COLUMN expires_at TYPE BIGINT USING EXTRACT(EPOCH FROM expires_at) * 1000,
  ALTER COLUMN created_at TYPE BIGINT USING EXTRACT(EPOCH FROM created_at) * 1000;

COMMENT ON COLUMN submissions.started_at IS 'Milliseconds since epoch (UTC) - when exam was started';
COMMENT ON COLUMN submissions.submitted_at IS 'Milliseconds since epoch (UTC) - when exam was submitted';
COMMENT ON COLUMN submissions.created_at IS 'Milliseconds since epoch (UTC) - when record was created';
COMMENT ON COLUMN exam_access_codes.expires_at IS 'Milliseconds since epoch (UTC) - when access code expires';
COMMENT ON COLUMN exam_access_codes.created_at IS 'Milliseconds since epoch (UTC) - when access code was created';
