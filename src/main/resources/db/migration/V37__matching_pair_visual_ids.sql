-- Matching items had no stable identity: the schema stored each pair as a flat
-- (left_item, right_item) row, so the frontend had to reconstruct which rows
-- belonged to the same visual node purely from their text/image content. Two
-- DISTINCT items that happened to share content (or two image-only items) were
-- collapsed into one — a teacher who added 4 options saw only 1 in the editor
-- and 3 in the exam.
--
-- These nullable columns persist the frontend's stable per-node visual id so
-- distinct items are never merged. Legacy rows keep NULL and fall back to the
-- old content-based grouping, so nothing breaks for existing questions.

ALTER TABLE matching_pairs
    ADD COLUMN left_visual_id  VARCHAR(64),
    ADD COLUMN right_visual_id VARCHAR(64);

ALTER TABLE bank_matching_pairs
    ADD COLUMN left_visual_id  VARCHAR(64),
    ADD COLUMN right_visual_id VARCHAR(64);
