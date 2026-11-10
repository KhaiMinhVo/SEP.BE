ALTER TABLE campaign ALTER COLUMN product_service DROP NOT NULL;
ALTER TABLE campaign ALTER COLUMN objective DROP NOT NULL;
ALTER TABLE campaign ADD COLUMN brief TEXT;
ALTER TABLE campaign ADD COLUMN creator_mode VARCHAR(16) CHECK(creator_mode IN ('SINGLE','MULTI'));
ALTER TABLE campaign ADD COLUMN target_creator_count INTEGER CHECK(target_creator_count>0);
ALTER TABLE campaign ADD COLUMN deliverables JSONB NOT NULL DEFAULT '[]';
ALTER TABLE campaign ADD COLUMN total_budget NUMERIC(19,2) CHECK(total_budget>0);
ALTER TABLE campaign ADD COLUMN currency VARCHAR(3) CHECK(currency='VND');
ALTER TABLE campaign ADD COLUMN creator_fee_min NUMERIC(19,2) CHECK(creator_fee_min>=0);
ALTER TABLE campaign ADD COLUMN creator_fee_max NUMERIC(19,2) CHECK(creator_fee_max>=0);
ALTER TABLE campaign ADD COLUMN campaign_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE campaign ADD CONSTRAINT ck_campaign_fee CHECK(creator_fee_min IS NULL OR creator_fee_max IS NULL OR creator_fee_min<=creator_fee_max);
ALTER TABLE campaign_context ADD COLUMN embedding_source_context_id UUID REFERENCES campaign_context(id);
UPDATE campaign_context c SET active=false WHERE active AND EXISTS(SELECT 1 FROM campaign_context newer WHERE newer.campaign_id=c.campaign_id AND newer.active AND newer.context_version>c.context_version);
CREATE UNIQUE INDEX ux_campaign_context_active ON campaign_context(campaign_id) WHERE active;
CREATE FUNCTION protect_campaign_context_content() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.campaign_id IS DISTINCT FROM OLD.campaign_id OR NEW.context_version IS DISTINCT FROM OLD.context_version OR NEW.context_data IS DISTINCT FROM OLD.context_data OR NEW.brand_context_id IS DISTINCT FROM OLD.brand_context_id OR NEW.embedding_source_context_id IS DISTINCT FROM OLD.embedding_source_context_id THEN
 RAISE EXCEPTION 'Campaign context content is immutable'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER campaign_context_immutable BEFORE UPDATE ON campaign_context FOR EACH ROW EXECUTE FUNCTION protect_campaign_context_content();
CREATE TABLE campaign_embedding_job (
 id UUID PRIMARY KEY,context_id UUID NOT NULL REFERENCES campaign_context(id),
 model_id VARCHAR(200) NOT NULL,model_version VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','PROCESSING','READY','FAILED')),
 attempts INT NOT NULL DEFAULT 0,next_attempt_at TIMESTAMPTZ NOT NULL,
 lease_until TIMESTAMPTZ,processing_token UUID,embedding vector(768),ready_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
 UNIQUE(context_id,model_id,model_version));
CREATE INDEX idx_campaign_embedding_due ON campaign_embedding_job(status,next_attempt_at,lease_until);
ALTER TABLE recommendation_run ADD COLUMN campaign_context_id UUID REFERENCES campaign_context(id);
ALTER TABLE recommendation_run ADD COLUMN embedding_context_id UUID REFERENCES campaign_context(id);
ALTER TABLE recommendation_run ADD COLUMN embedding_job_id UUID REFERENCES campaign_embedding_job(id);
ALTER TABLE recommendation_run ADD COLUMN embedding_model VARCHAR(200);
ALTER TABLE recommendation_run ADD COLUMN embedding_model_version VARCHAR(100);
ALTER TABLE recommendation_run ADD COLUMN candidate_source VARCHAR(16);
