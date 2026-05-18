-- Historical rows used the literal "WELCOME_GIFT" as transaction_id, which collides with
-- the unique constraint uq_user_subscriptions_transaction_id once a second teacher
-- registers. Postfix each existing row with its user_id so old rows match the new
-- "WELCOME_GIFT_<id>" scheme written by AuthService/GoogleAuthService.
UPDATE user_subscriptions
   SET transaction_id = 'WELCOME_GIFT_' || user_id
 WHERE transaction_id = 'WELCOME_GIFT';
