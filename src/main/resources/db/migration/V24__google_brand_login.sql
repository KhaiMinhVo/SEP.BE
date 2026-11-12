CREATE TABLE google_login_session (
    id UUID PRIMARY KEY,
    nonce_hash VARCHAR(64) NOT NULL,
    browser_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX idx_google_login_session_expiry ON google_login_session(expires_at);
