-- Append-Only Historical Evidence & Versioning Support (SP-01)
ALTER TABLE historical_campaign ADD COLUMN IF NOT EXISTS evidence_root_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE historical_campaign ADD COLUMN IF NOT EXISTS revision_number BIGINT NOT NULL DEFAULT 1;
ALTER TABLE historical_campaign ADD COLUMN IF NOT EXISTS schema_version VARCHAR(16) NOT NULL DEFAULT '1.0';
ALTER TABLE historical_campaign ADD COLUMN IF NOT EXISTS supersedes_id UUID REFERENCES historical_campaign(id);
ALTER TABLE historical_campaign ADD COLUMN IF NOT EXISTS business_occurred_at TIMESTAMP;
ALTER TABLE historical_campaign ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

-- Backfill legacy records: root_id = id, business_occurred_at = recorded_at
UPDATE historical_campaign SET evidence_root_id = id WHERE evidence_root_id = id OR evidence_root_id IS NULL;
UPDATE historical_campaign SET business_occurred_at = recorded_at WHERE business_occurred_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_historical_root_rev ON historical_campaign(evidence_root_id, revision_number);
CREATE INDEX IF NOT EXISTS idx_historical_creator_asof ON historical_campaign(creator_id, recorded_at, status);
CREATE INDEX IF NOT EXISTS idx_historical_master_asof ON historical_campaign(master_id, recorded_at, status);
CREATE UNIQUE INDEX IF NOT EXISTS ux_historical_root_revision ON historical_campaign(evidence_root_id, revision_number);
