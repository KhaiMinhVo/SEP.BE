-- Preserve creator_rate_card and all historical data/audits. Only retire live authorization.
-- Increment affected configurations before removing their overrides so stale Admin PATCHes fail.
UPDATE role_permission_config config
SET version = version + 1
WHERE EXISTS (
    SELECT 1 FROM role_permission_override override
    WHERE override.role = config.role AND override.permission = 'MANAGE_OWN_RATE_CARD'
);
DELETE FROM role_permission_override WHERE permission = 'MANAGE_OWN_RATE_CARD';
