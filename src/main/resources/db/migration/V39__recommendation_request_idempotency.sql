CREATE TABLE ai_recommendation_request (
    campaign_id UUID NOT NULL REFERENCES campaign(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (campaign_id, idempotency_key)
);
