-- V30: subject category for the exam-creation picker filter pills.
-- Plain display string on exam_subjects; admins set it from the panel and the
-- frontend builds its filter pills from the distinct values. Backfill the
-- well-known default subjects so the pills are populated from day one.
-- Idempotent: re-runs are harmless.

ALTER TABLE exam_subjects ADD COLUMN IF NOT EXISTS category VARCHAR(100);

UPDATE exam_subjects SET category = 'Dillər'
WHERE category IS NULL
  AND name IN ('Azərbaycan dili', 'İngilis dili', 'Rus dili',
               'Alman dili', 'Fransız dili', 'Xarici dil');

UPDATE exam_subjects SET category = 'Dəqiq elmlər'
WHERE category IS NULL
  AND name IN ('Riyaziyyat', 'Cəbr', 'Həndəsə', 'Fizika', 'İnformatika',
               'Informatika', 'Məntiq');

UPDATE exam_subjects SET category = 'Təbiət'
WHERE category IS NULL
  AND name IN ('Kimya', 'Biologiya', 'Coğrafiya');

UPDATE exam_subjects SET category = 'Humanitar'
WHERE category IS NULL
  AND name IN ('Ədəbiyyat', 'Tarix', 'Qanunvericilik', 'İqtisadiyyat');
