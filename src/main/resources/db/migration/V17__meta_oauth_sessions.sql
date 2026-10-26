CREATE TABLE meta_oauth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES "user"(id),
    auth_version BIGINT NOT NULL,
    platform VARCHAR(20) NOT NULL CHECK (platform IN ('FACEBOOK','INSTAGRAM')),
    state_hash VARCHAR(64) NOT NULL UNIQUE,
    browser_binding_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('NEW','EXCHANGING','READY','COMPLETED','FAILED','EXPIRED')),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    target_creator_id UUID REFERENCES creator(id),
    invitation_id UUID REFERENCES creator_invitation(id),
    payload_ciphertext TEXT,
    confirmed_account_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_meta_oauth_session_owner ON meta_oauth_session(user_id,created_at);
CREATE INDEX ix_meta_oauth_session_expiry ON meta_oauth_session(expires_at,status);
