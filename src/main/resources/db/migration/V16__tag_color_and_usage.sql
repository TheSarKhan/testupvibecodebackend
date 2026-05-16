ALTER TABLE tags ADD COLUMN IF NOT EXISTS color VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_exam_tags_tag ON exam_tags (tag);
