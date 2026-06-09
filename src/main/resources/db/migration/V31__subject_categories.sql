-- V31: admin-managed subject categories.
-- Replaces the plain-string exam_subjects.category column (V30) with a proper
-- subject_categories table the admin can manage; exam_subjects references it
-- via category_id (ON DELETE SET NULL so removing a category never removes
-- subjects). Existing category strings are preserved: any distinct value in
-- use becomes a category row before the old column is dropped.
-- Idempotent: re-runs are harmless.

CREATE TABLE IF NOT EXISTS subject_categories (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    order_index INT,
    color       VARCHAR(20),
    is_default  BOOLEAN NOT NULL DEFAULT FALSE
);

-- Seed the standard set (deletion-protected via is_default).
INSERT INTO subject_categories (name, order_index, is_default) VALUES
    ('Dəqiq elmlər', 1, TRUE),
    ('Təbiət',       2, TRUE),
    ('Humanitar',    3, TRUE),
    ('Dillər',       4, TRUE)
ON CONFLICT (name) DO NOTHING;

ALTER TABLE exam_subjects
    ADD COLUMN IF NOT EXISTS category_id BIGINT REFERENCES subject_categories(id) ON DELETE SET NULL;

-- Preserve any custom category strings already assigned from the admin panel.
INSERT INTO subject_categories (name, is_default)
SELECT DISTINCT es.category, FALSE
FROM exam_subjects es
WHERE es.category IS NOT NULL AND es.category <> ''
ON CONFLICT (name) DO NOTHING;

-- Re-point subjects from the string column onto the FK.
UPDATE exam_subjects es
SET category_id = sc.id
FROM subject_categories sc
WHERE es.category_id IS NULL
  AND es.category IS NOT NULL
  AND es.category = sc.name;

-- The string column is fully superseded.
ALTER TABLE exam_subjects DROP COLUMN IF EXISTS category;
