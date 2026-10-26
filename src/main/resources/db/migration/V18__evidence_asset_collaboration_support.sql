-- Migration V18: Evidence Asset Collaboration Support (Flow 5 Phase 3)

-- 1. Make outcome_id nullable on evidence_asset so evidence can be submitted while IN_PROGRESS
ALTER TABLE evidence_asset ALTER COLUMN outcome_id DROP NOT NULL;

-- 2. Add collaboration_id to evidence_asset
ALTER TABLE evidence_asset ADD COLUMN IF NOT EXISTS collaboration_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_evidence_collaboration') THEN
        ALTER TABLE evidence_asset ADD CONSTRAINT fk_evidence_collaboration
            FOREIGN KEY (collaboration_id) REFERENCES collaboration(id) ON DELETE CASCADE;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_evidence_collaboration ON evidence_asset(collaboration_id);
