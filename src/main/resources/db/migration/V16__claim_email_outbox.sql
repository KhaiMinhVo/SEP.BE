CREATE TABLE claim_email_outbox (
    id UUID PRIMARY KEY,
    invitation_id UUID NOT NULL UNIQUE REFERENCES creator_invitation(id),
    payload_ciphertext TEXT,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED','PROCESSING','SENT','FAILED','CANCELLED','EXPIRED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    cycle_attempts INTEGER NOT NULL DEFAULT 0 CHECK (cycle_attempts BETWEEN 0 AND 5),
    next_attempt_at TIMESTAMP,
    last_attempt_at TIMESTAMP,
    last_sent_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    lease_until TIMESTAMP,
    lease_token UUID,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_claim_email_due ON claim_email_outbox(status,next_attempt_at,lease_until);
CREATE INDEX ix_claim_email_expiry ON claim_email_outbox(expires_at) WHERE payload_ciphertext IS NOT NULL;
CREATE TABLE claim_email_retry (
    job_id UUID NOT NULL REFERENCES claim_email_outbox(id),
    requested_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_claim_email_retry_job_time ON claim_email_retry(job_id,requested_at);
