-- Show/hide toggle for templates. Hidden templates stay in the admin panel but
-- disappear from the teacher-facing template picker. DEFAULT TRUE keeps every
-- existing template visible.
ALTER TABLE templates ADD COLUMN IF NOT EXISTS visible BOOLEAN NOT NULL DEFAULT TRUE;
