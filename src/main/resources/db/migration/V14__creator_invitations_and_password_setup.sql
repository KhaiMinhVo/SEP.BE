-- Preserve all historical accounts, identities and exchange codes.
CREATE TABLE creator_invitation (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    invited_by UUID NOT NULL REFERENCES "user"(id)
);
CREATE TABLE creator_invitation_delivery (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_creator_invitation_delivery_email_time ON creator_invitation_delivery(email,sent_at);
CREATE TABLE password_setup_otp (
    user_id UUID PRIMARY KEY REFERENCES "user"(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    consumed_at TIMESTAMP WITH TIME ZONE
);
