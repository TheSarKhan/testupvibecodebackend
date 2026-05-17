ALTER TABLE exams
    ADD COLUMN IF NOT EXISTS explanation_video_url VARCHAR(500);
