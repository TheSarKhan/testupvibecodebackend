-- Audit log timestamps were written as wall-clock time in the JVM's zone, and the
-- production host runs Europe/Berlin. The database is Etc/UTC and the frontend
-- reads a naked timestamp as UTC, so every row sat two hours in the future and
-- the admin panel rendered them all as "İndicə".
--
-- TestupApplication now pins the JVM to UTC, which fixes new rows. This converts
-- the rows written before that. Going through `AT TIME ZONE 'Europe/Berlin'`
-- resolves each timestamp against the offset in force on that date, so rows
-- written in winter (CET, +1) and summer (CEST, +2) are both corrected — a flat
-- "minus 2 hours" would have been wrong for half the year.
--
-- Scope is deliberately audit_logs only. Other tables carry the same skew, but
-- shifting subscription or payment timestamps changes business state (e.g. who
-- counts as an active subscriber), so those are left for a deliberate decision.
UPDATE audit_logs
SET created_at = (created_at AT TIME ZONE 'Europe/Berlin') AT TIME ZONE 'UTC'
WHERE created_at IS NOT NULL;
