-- Migration V34: Add Creator Fee Columns and Discovery Keyword Management

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

-- 3. Create table discovery_keyword (Manager-managed Seeds & Blacklists)
CREATE TABLE IF NOT EXISTS discovery_keyword (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keyword VARCHAR(255) NOT NULL,
    category VARCHAR(100),
    keyword_type VARCHAR(50) NOT NULL DEFAULT 'TARGET',
    platform VARCHAR(50) NOT NULL DEFAULT 'TIKTOK',
    is_active BOOLEAN NOT NULL DEFAULT true,
    creator_count BIGINT NOT NULL DEFAULT 0,
    last_crawled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_discovery_keyword_active ON discovery_keyword (is_active, keyword_type);
CREATE INDEX IF NOT EXISTS idx_discovery_keyword_category ON discovery_keyword (category);

