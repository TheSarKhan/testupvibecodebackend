-- Lets one section hold SEVERAL passages of the same type — e.g. DİM Azərbaycan
-- dili has TWO reading texts, each with 5 qapalı + 5 açıq tapşırıq.
--
-- Until now typeCount rows were grouped by passage_type alone, so every "TEXT"
-- row collapsed into a single passage. passage_group distinguishes them:
-- rows sharing (passage_type, passage_group) belong to the same passage.
-- NULL keeps the previous behaviour (one passage per type), so existing
-- templates are unaffected.
ALTER TABLE template_section_type_counts ADD COLUMN IF NOT EXISTS passage_group INTEGER;
