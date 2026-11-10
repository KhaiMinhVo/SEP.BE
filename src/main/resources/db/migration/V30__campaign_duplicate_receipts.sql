CREATE TABLE campaign_duplicate_request (
 actor_id uuid NOT NULL REFERENCES "user"(id), request_key varchar(100) NOT NULL,
 request_hash varchar(64) NOT NULL, source_campaign_id uuid NOT NULL REFERENCES campaign(id),
 source_context_id uuid REFERENCES campaign_context(id), source_revision bigint NOT NULL,
 source_kind varchar(32) NOT NULL CHECK(source_kind IN ('CONTEXT_SNAPSHOT','CURRENT_DRAFT','CURRENT_RECORD_FALLBACK')),
 target_campaign_id uuid NOT NULL UNIQUE REFERENCES campaign(id), response jsonb NOT NULL,
 created_at timestamptz NOT NULL, PRIMARY KEY(actor_id,request_key));
CREATE INDEX idx_campaign_duplicate_source ON campaign_duplicate_request(source_campaign_id,created_at);
-- Historical campaigns deliberately have no inferred copy origin.
