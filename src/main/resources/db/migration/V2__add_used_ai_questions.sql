ALTER TABLE subscription_usages
    ADD COLUMN IF NOT EXISTS used_ai_questions integer NOT NULL DEFAULT 0;
