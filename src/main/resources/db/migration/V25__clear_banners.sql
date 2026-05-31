-- Wipe all admin-uploaded banners (the "Reklamlar" the user wants gone).
-- Banners were inserted exclusively through AdminBannerService, never seeded
-- from code or migrations, so the only place they live is in the `banners`
-- table itself. This migration runs once on the next deploy and clears them.
-- The admin panel keeps working; it just shows an empty list.
DELETE FROM banners;
