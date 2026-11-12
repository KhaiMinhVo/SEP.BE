-- Migration V17: Recommendation Run & Shortlist Enhancements (Flow 3 Phase 1)

-- 1. Create recommendation_run table for tracking AI Recommendation runs and context versions
CREATE TABLE IF NOT EXISTS recommendation_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL REFERENCES campaign(id) ON DELETE CASCADE,
    brand_profile_id UUID NOT NULL REFERENCES brand_profile(id) ON DELETE CASCADE,
    context_version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    candidates_count INTEGER NOT NULL DEFAULT 0,
    query_brief TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rec_run_campaign ON recommendation_run(campaign_id);
CREATE INDEX IF NOT EXISTS idx_rec_run_brand ON recommendation_run(brand_profile_id);

-- 2. Enhance shortlist_item table with status, source, and recommendation_run_id
ALTER TABLE shortlist_item ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'SAVED';
ALTER TABLE shortlist_item ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'MANUAL_DISCOVERY';
ALTER TABLE shortlist_item ADD COLUMN IF NOT EXISTS recommendation_run_id UUID REFERENCES recommendation_run(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_shortlist_campaign_status ON shortlist_item(campaign_id, status);
CREATE INDEX IF NOT EXISTS idx_shortlist_rec_run ON shortlist_item(recommendation_run_id);

-- 3. Ensure unique constraint on (campaign_id, creator_id)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ux_shortlist_campaign_creator'
    ) THEN
        ALTER TABLE shortlist_item ADD CONSTRAINT ux_shortlist_campaign_creator UNIQUE (campaign_id, creator_id);
    END IF;
END $$;
