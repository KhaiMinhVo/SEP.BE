-- V8__claim_based_identity_foundation.sql

-- 1. Tạo bảng Creator Master (Đại diện cho KOC con người thật, quan hệ 1-1 với User Role CREATOR)
CREATE TABLE IF NOT EXISTS creator_master (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES "user"(id),
    canonical_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, SUSPENDED
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_creator_master_user ON creator_master(user_id);

-- 2. Đảm bảo xóa bỏ hoàn toàn owner_user_id và thiết lập master_id trên bảng creator
ALTER TABLE creator DROP CONSTRAINT IF EXISTS ck_creator_authorization;
DROP INDEX IF EXISTS ix_creator_owner;
ALTER TABLE creator DROP COLUMN IF EXISTS owner_user_id;

ALTER TABLE creator ADD COLUMN IF NOT EXISTS master_id UUID;
ALTER TABLE creator ADD COLUMN IF NOT EXISTS is_stable_id BOOLEAN NOT NULL DEFAULT TRUE;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_creator_master') THEN
        ALTER TABLE creator ADD CONSTRAINT fk_creator_master 
            FOREIGN KEY (master_id) REFERENCES creator_master(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_creator_master_id ON creator(master_id);

-- Check Constraint mới: chuẩn hóa theo master_id
ALTER TABLE creator ADD CONSTRAINT ck_creator_authorization CHECK (
    (source_type = 'PUBLIC' AND master_id IS NULL AND authorized_at IS NULL) OR
    (source_type = 'AUTHORIZED' AND master_id IS NOT NULL AND authorized_at IS NOT NULL)
);

-- 3. Bổ sung Provenance cho bảng public_creator_metric
ALTER TABLE public_creator_metric ADD COLUMN IF NOT EXISTS crawl_run_id VARCHAR(64);
ALTER TABLE public_creator_metric ADD COLUMN IF NOT EXISTS observed_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_metric_crawl_run ON public_creator_metric(creator_id, crawl_run_id);

-- 4. Bổ sung Dual Reference (Nullable) cho M1 & Shortlist / Relationship / Collaboration / Historical Campaign
ALTER TABLE shortlist_item ADD COLUMN IF NOT EXISTS master_id UUID;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_shortlist_master') THEN
        ALTER TABLE shortlist_item ADD CONSTRAINT fk_shortlist_master 
            FOREIGN KEY (master_id) REFERENCES creator_master(id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_shortlist_master ON shortlist_item(master_id);

ALTER TABLE relationship ADD COLUMN IF NOT EXISTS master_id UUID;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_relationship_master') THEN
        ALTER TABLE relationship ADD CONSTRAINT fk_relationship_master 
            FOREIGN KEY (master_id) REFERENCES creator_master(id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_relationship_master ON relationship(master_id);

ALTER TABLE collaboration ADD COLUMN IF NOT EXISTS master_id UUID;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_collaboration_master') THEN
        ALTER TABLE collaboration ADD CONSTRAINT fk_collaboration_master 
            FOREIGN KEY (master_id) REFERENCES creator_master(id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_collaboration_master ON collaboration(master_id);

ALTER TABLE historical_campaign ADD COLUMN IF NOT EXISTS master_id UUID;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_historical_master') THEN
        ALTER TABLE historical_campaign ADD CONSTRAINT fk_historical_master 
            FOREIGN KEY (master_id) REFERENCES creator_master(id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_historical_master ON historical_campaign(master_id);
