ALTER TABLE "user" ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE;
CREATE TABLE email_verification_otp (
    user_id UUID PRIMARY KEY REFERENCES "user"(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    consumed_at TIMESTAMP WITH TIME ZONE,
    last_sent_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE email_verification_delivery (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_verification_delivery_user_time ON email_verification_delivery(user_id,sent_at);
