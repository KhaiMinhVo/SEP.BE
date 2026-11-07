CREATE TABLE tiktok_oauth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES "user"(id),
    auth_version BIGINT NOT NULL,
    client_key VARCHAR(255) NOT NULL,
    state_hash VARCHAR(64) NOT NULL UNIQUE,
    browser_binding_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('NEW','EXCHANGING','READY','FAILED','EXPIRED')),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    target_creator_id UUID REFERENCES creator(id),
    invitation_id UUID REFERENCES creator_invitation(id),
    open_id VARCHAR(255), username VARCHAR(255), display_name VARCHAR(255),
    created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_tiktok_session_profile CHECK (
        (status='READY' AND open_id IS NOT NULL AND username IS NOT NULL)
        OR (status<>'READY' AND open_id IS NULL AND username IS NULL AND display_name IS NULL))
);
CREATE INDEX ix_tiktok_session_owner ON tiktok_oauth_session(user_id,created_at);
CREATE INDEX ix_tiktok_session_expiry ON tiktok_oauth_session(expires_at,status);
