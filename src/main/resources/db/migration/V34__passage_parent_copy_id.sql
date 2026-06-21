-- ════════════════════════════════════════════════════════════════════════════
-- V34 — passages.parent_copy_id (birgə imtahan passage dedup)
-- Problem: birgə imtahanda admin passage suallarını TƏK-TƏK təsdiqləyəndə
-- (approveQuestion) hər çağırış yeni boş passageMap ötürürdü → eyni mətn/dinləmə
-- materialı hər təsdiqlənən sual üçün parent imtahanda təkrar yaranırdı.
-- Həll: draft passage hansı parent passage-ə köçürüldüyünü yadda saxlasın
-- (Question.parent_copy_id pattern-i kimi) ki, sonrakı təsdiqlər həmin parent
-- passage-i təkrar istifadə etsin, dublikat yaratmasın.
-- ════════════════════════════════════════════════════════════════════════════

ALTER TABLE passages ADD COLUMN parent_copy_id BIGINT;
