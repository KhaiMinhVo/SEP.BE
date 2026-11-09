-- Migration V20: Add confirmation_source column to collaboration table
-- 1. Add column nullable first
ALTER TABLE collaboration ADD COLUMN IF NOT EXISTS confirmation_source VARCHAR(64);

-- 2. Historical records created before this policy are backfilled as LEGACY_UNVERIFIED
-- to avoid retroactively claiming external agreement without verifiable evidence
UPDATE collaboration 
SET confirmation_source = 'LEGACY_UNVERIFIED' 
WHERE confirmation_source IS NULL;

-- 3. Set default to BRAND_RECORDED_EXTERNAL for all new records and enforce NOT NULL
ALTER TABLE collaboration ALTER COLUMN confirmation_source SET DEFAULT 'BRAND_RECORDED_EXTERNAL';
ALTER TABLE collaboration ALTER COLUMN confirmation_source SET NOT NULL;
