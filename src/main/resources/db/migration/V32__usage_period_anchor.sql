-- V32 (BUG-24): anchor monthly usage to the subscription's own 30-day cycle
-- instead of the calendar month. Usage rows are keyed by the START DATE of the
-- current 30-day period ("2026-06-28") computed from user_subscriptions.usage_anchor;
-- a renewal re-anchors the cycle so the user immediately gets a fresh limit.
-- Idempotent: re-runs are harmless.

-- 1. Per-subscription cycle anchor; existing subscriptions anchor at their start.
ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS usage_anchor TIMESTAMP;
UPDATE user_subscriptions SET usage_anchor = start_date WHERE usage_anchor IS NULL;

-- 2. Period keys are dates (10 chars); the old calendar form was 7.
ALTER TABLE subscription_usages ALTER COLUMN month_year TYPE VARCHAR(10);

-- 3. Preserve the CURRENT calendar month's counters: move each active
--    subscription's current-month row onto its computed current period key so
--    nobody's usage silently resets (or double-counts) at deploy time.
--    2592000 = 30 days in seconds.
UPDATE subscription_usages su
SET month_year = to_char(
        (us.usage_anchor
         + (GREATEST(floor(extract(epoch from (now() - us.usage_anchor)) / 2592000.0), 0)
            * interval '30 days'))::date,
        'YYYY-MM-DD')
FROM user_subscriptions us
WHERE su.user_subscription_id = us.id
  AND us.is_active = TRUE
  AND su.month_year = to_char(now(), 'YYYY-MM');
