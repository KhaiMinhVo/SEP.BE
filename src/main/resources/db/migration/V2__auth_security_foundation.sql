ALTER TABLE users ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    jti UUID NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    replaced_by_token_id UUID REFERENCES refresh_tokens(id),
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX ix_refresh_tokens_expiry ON refresh_tokens(expires_at);
CREATE INDEX ix_refresh_tokens_active ON refresh_tokens(user_id, revoked_at);

-- V1 shipped public demo credentials. Keep rows for referential safety but disable them.
UPDATE users SET status='INACTIVE', updated_at=CURRENT_TIMESTAMP
WHERE id IN ('550e8400-e29b-41d4-a716-446655440000','550e8400-e29b-41d4-a716-446655440001');
