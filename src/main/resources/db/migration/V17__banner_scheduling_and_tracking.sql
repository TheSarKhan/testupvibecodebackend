ALTER TABLE banners
    ADD COLUMN IF NOT EXISTS start_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS end_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS impression_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS click_count BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_banners_active_window
    ON banners (is_active, start_at, end_at);
