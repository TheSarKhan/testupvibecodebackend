-- V28: Stripe-style split of SubscriptionPlan.
--
-- Before: one subscription_plans row per tier×duration (Basic, "Basic 3 ay", ...).
-- After:  one row per TIER (Basic), with durations + amounts moved into a new
--         subscription_plan_prices child table.
--
-- The variant rows ("Basic 3 ay" etc.) share their base tier's `level` and only
-- the base rows have duration_months = 1, so we match variant→base on level.
-- Every statement is idempotent so a re-run is harmless.

-- 1. New child table: one price per (tier, duration).
CREATE TABLE IF NOT EXISTS subscription_plan_prices (
    id              BIGSERIAL PRIMARY KEY,
    plan_id         BIGINT  NOT NULL REFERENCES subscription_plans(id) ON DELETE CASCADE,
    duration_months INT     NOT NULL,
    price           DOUBLE PRECISION NOT NULL,
    visible         BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_plan_price_plan_duration UNIQUE (plan_id, duration_months)
);

-- 2. Base tiers (duration_months = 1) -> their own 1-month price row.
INSERT INTO subscription_plan_prices (plan_id, duration_months, price, visible)
SELECT sp.id, 1, sp.price, sp.visible
FROM subscription_plans sp
WHERE sp.duration_months = 1
ON CONFLICT (plan_id, duration_months) DO NOTHING;

-- 3. Variant rows (duration_months > 1) -> price row ON THE BASE TIER, matched by level.
INSERT INTO subscription_plan_prices (plan_id, duration_months, price, visible)
SELECT base.id, variant.duration_months, variant.price, variant.visible
FROM subscription_plans variant
JOIN subscription_plans base
      ON base.level = variant.level
     AND base.duration_months = 1
WHERE variant.duration_months > 1
ON CONFLICT (plan_id, duration_months) DO NOTHING;

-- 4. Re-point FK references from variant rows onto their base tier (BEFORE delete)
--    so no paid subscription / order is orphaned.
UPDATE user_subscriptions us
SET plan_id = base.id
FROM subscription_plans variant
JOIN subscription_plans base
      ON base.level = variant.level
     AND base.duration_months = 1
WHERE us.plan_id = variant.id
  AND variant.duration_months > 1;

UPDATE payment_orders po
SET plan_id = base.id
FROM subscription_plans variant
JOIN subscription_plans base
      ON base.level = variant.level
     AND base.duration_months = 1
WHERE po.plan_id = variant.id
  AND variant.duration_months > 1;

-- 5. Durable billing duration on the subscription itself (backfilled by the app
--    on future purchases; NULL on legacy rows is handled by the proration fallback).
ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS duration_months INT;

-- 6. EDGE CASE: a variant whose level has NO base (duration=1) row. Don't orphan it
--    — promote it to a standalone tier by giving it its own price row, and keep it
--    (step 7 only deletes variants that HAD a base match).
INSERT INTO subscription_plan_prices (plan_id, duration_months, price, visible)
SELECT v.id, v.duration_months, v.price, v.visible
FROM subscription_plans v
WHERE v.duration_months > 1
  AND NOT EXISTS (
        SELECT 1 FROM subscription_plans b
        WHERE b.level = v.level AND b.duration_months = 1)
ON CONFLICT (plan_id, duration_months) DO NOTHING;

-- 7. Delete the now-collapsed variant rows (only those that mapped onto a base).
DELETE FROM subscription_plans v
WHERE v.duration_months > 1
  AND EXISTS (
        SELECT 1 FROM subscription_plans b
        WHERE b.level = v.level AND b.duration_months = 1);

-- 8. Drop the columns that moved to subscription_plan_prices.
ALTER TABLE subscription_plans DROP COLUMN IF EXISTS price;
ALTER TABLE subscription_plans DROP COLUMN IF EXISTS duration_months;

-- 9. Restore tier-name uniqueness (V26 dropped it to allow per-duration rows;
--    with variants collapsed, tier names are unique again).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'subscription_plans_name_key') THEN
        ALTER TABLE subscription_plans ADD CONSTRAINT subscription_plans_name_key UNIQUE (name);
    END IF;
END $$;
