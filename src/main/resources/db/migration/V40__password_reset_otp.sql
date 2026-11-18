CREATE TABLE password_reset_otp (
    user_id UUID PRIMARY KEY REFERENCES "user"(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    consumed_at TIMESTAMP WITH TIME ZONE,
    auth_version BIGINT NOT NULL
);
