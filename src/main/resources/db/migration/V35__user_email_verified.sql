-- V35: email verification flag for users.
-- New registrations must confirm a one-time code sent to their email before the
-- account is usable (login is gated on email_verified). All accounts that ALREADY
-- exist when this runs are grandfathered as verified (DEFAULT TRUE) so the new
-- gate never locks out current users. Google-OAuth accounts are created verified
-- in code (the provider already proved the address). Only the public
-- email/password registration path sets this to false and then flips it to true
-- once the OTP is confirmed.
-- Idempotent: re-runs are harmless.
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT TRUE;
