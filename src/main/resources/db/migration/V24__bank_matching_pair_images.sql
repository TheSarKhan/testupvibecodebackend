-- Bank matching pairs were silently dropping image attachments because the
-- schema only stored leftItem / rightItem text. A teacher who built a
-- visual matching question (e.g. flag → country, where the flag is an
-- image) then saved the question to the bank lost the images and had to
-- re-attach them every time they re-used the question.
--
-- Adds the same columns the regular `matching_pairs` table already has.

ALTER TABLE bank_matching_pairs
    ADD COLUMN attached_image_left TEXT,
    ADD COLUMN attached_image_right TEXT;
