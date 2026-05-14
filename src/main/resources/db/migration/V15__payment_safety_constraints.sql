-- Payment safety constraints
--
-- 1. At most one active subscription per user.
--    Prevents the race in PaymentController.activateOrder where two concurrent
--    KB callbacks could both find "no active subscription" and each insert a
--    new row, leaving the user with two active subscriptions in parallel.
--
-- 2. transactionId is unique among non-null values.
--    Provides a hard guarantee that a Kapital Bank orderId cannot activate
--    a subscription more than once even if application-level idempotency
--    guards somehow fail.
--
-- 3. payment_orders.status restricted to known values.
--    Catches typos / bad updates at the DB layer.

-- 1. Partial unique index on user_id where active = true
--    (Postgres-only; H2 dev DB won't enforce this but production will.)
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_subscriptions_one_active_per_user
    ON user_subscriptions (user_id)
    WHERE is_active = true;

-- 2. Partial unique index on transaction_id (NULLs allowed, duplicates forbidden)
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_subscriptions_transaction_id
    ON user_subscriptions (transaction_id)
    WHERE transaction_id IS NOT NULL;

-- 3. Whitelist the status values for payment_orders
--    Drop existing check constraint if one is present, then add the canonical one.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'payment_orders' AND constraint_name = 'chk_payment_orders_status'
    ) THEN
        ALTER TABLE payment_orders DROP CONSTRAINT chk_payment_orders_status;
    END IF;
END $$;

ALTER TABLE payment_orders
    ADD CONSTRAINT chk_payment_orders_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'PAID', 'FAILED', 'CANCELLED'));
