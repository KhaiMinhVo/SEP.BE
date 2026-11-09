ALTER TABLE brand_profile ADD COLUMN profile_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE brand_profile ADD COLUMN positioning VARCHAR(1000);
ALTER TABLE brand_profile ADD COLUMN guidelines VARCHAR(4000);
ALTER TABLE brand_context DROP CONSTRAINT brand_context_brand_profile_id_key;
ALTER TABLE brand_context ADD CONSTRAINT ux_brand_context_version UNIQUE(brand_profile_id,context_version);

CREATE TABLE brand_embedding_job (
 id UUID PRIMARY KEY,
 context_id UUID NOT NULL REFERENCES brand_context(id),
 model_id VARCHAR(200) NOT NULL, model_version VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','PROCESSING','READY','FAILED')),
 attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL,
 lease_until TIMESTAMPTZ, processing_token UUID,
 embedding vector(768), ready_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 UNIQUE(context_id,model_id,model_version));
CREATE INDEX idx_brand_embedding_due ON brand_embedding_job(status,next_attempt_at,lease_until);

ALTER TABLE campaign_context ADD COLUMN brand_context_id UUID REFERENCES brand_context(id);
ALTER TABLE recommendation_run ADD COLUMN brand_context_id UUID REFERENCES brand_context(id);
ALTER TABLE recommendation_run ADD COLUMN brand_context_version INT;
ALTER TABLE recommendation_run ADD COLUMN scoring_policy_version VARCHAR(100);
ALTER TABLE recommendation_run ADD COLUMN scoring_snapshot JSONB;
CREATE INDEX idx_campaign_brand_context ON campaign_context(brand_context_id);
CREATE INDEX idx_run_brand_context ON recommendation_run(brand_context_id);
CREATE UNIQUE INDEX ux_brand_fit_cohort ON brand_pattern(brand_profile_id,platform,niche)
 WHERE pattern_type='BRAND_FIT_BY_PLATFORM_NICHE';

-- Existing contexts/patterns/runs are retained exactly; no invented historical snapshots.
CREATE FUNCTION prevent_brand_context_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Brand context versions are immutable'; END $$;
CREATE TRIGGER brand_context_immutable BEFORE UPDATE ON brand_context
FOR EACH ROW EXECUTE FUNCTION prevent_brand_context_update();
