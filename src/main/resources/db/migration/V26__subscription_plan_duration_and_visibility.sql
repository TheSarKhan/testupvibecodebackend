-- Add per-plan billing duration + admin-controlled visibility.
-- Existing rows (Free / Basic / Limitsiz) become the 1-month variants and
-- stay visible by default; new multi-month SKUs are seeded from Java in
-- DataSeeder.seedSubscriptionPlans so this migration only sets up the schema.
ALTER TABLE subscription_plans
    ADD COLUMN IF NOT EXISTS duration_months INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS visible BOOLEAN NOT NULL DEFAULT TRUE;

-- The (name) unique constraint was fine for "Basic / Limitsiz / Free", but
-- with per-duration rows we need composite uniqueness: same plan family + same
-- duration shouldn't be duplicable, but "Basic 1 ay" and "Basic 3 ay" must
-- coexist. Drop the old unique constraint if present; the new index is added
-- after the seeder ensures no name conflicts.
ALTER TABLE subscription_plans DROP CONSTRAINT IF EXISTS subscription_plans_name_key;
