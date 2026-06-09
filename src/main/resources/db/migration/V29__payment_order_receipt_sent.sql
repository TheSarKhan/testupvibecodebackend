-- Track whether the purchase-receipt email has been sent for an order so verify
-- retries, the KB callback, and the recovery scheduler don't send duplicates.
ALTER TABLE payment_orders
    ADD COLUMN IF NOT EXISTS receipt_sent BOOLEAN NOT NULL DEFAULT FALSE;
