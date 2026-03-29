ALTER TABLE subscription_plans
    ADD COLUMN IF NOT EXISTS use_ai_exam_generation boolean NOT NULL DEFAULT false;
