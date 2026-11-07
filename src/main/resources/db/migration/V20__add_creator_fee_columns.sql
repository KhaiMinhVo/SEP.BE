-- Migration V20: Add Creator Fee Columns and Backfill Existing Data
-- 1. Add fee columns to public_creator_metric
ALTER TABLE public_creator_metric
    ADD COLUMN IF NOT EXISTS estimated_fee_min NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS estimated_fee_max NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS actual_fee NUMERIC(19, 2);

-- 2. Backfill existing records based on Follower Fee Tiers
UPDATE public_creator_metric
SET 
    estimated_fee_min = CASE
        WHEN COALESCE(followers, 0) < 10000 THEN 200000
        WHEN COALESCE(followers, 0) < 50000 THEN 1000000
        WHEN COALESCE(followers, 0) < 100000 THEN 2500000
        WHEN COALESCE(followers, 0) < 500000 THEN 5000000
        WHEN COALESCE(followers, 0) < 1000000 THEN 15000000
        ELSE 30000000
    END,
    estimated_fee_max = CASE
        WHEN COALESCE(followers, 0) < 10000 THEN 500000
        WHEN COALESCE(followers, 0) < 50000 THEN 2500000
        WHEN COALESCE(followers, 0) < 100000 THEN 5000000
        WHEN COALESCE(followers, 0) < 500000 THEN 15000000
        WHEN COALESCE(followers, 0) < 1000000 THEN 30000000
        ELSE 60000000
    END
WHERE estimated_fee_min IS NULL OR estimated_fee_max IS NULL;

