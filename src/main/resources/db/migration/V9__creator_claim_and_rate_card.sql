ALTER TABLE "user" DROP CONSTRAINT ck_user_role;
ALTER TABLE "user" ADD CONSTRAINT ck_user_role CHECK (role IN ('BRAND','CREATOR','DATA_MANAGER','ADMIN'));
ALTER TABLE auth_exchange_code ADD COLUMN login_role VARCHAR(20) NOT NULL DEFAULT 'BRAND';
ALTER TABLE auth_exchange_code ADD CONSTRAINT ck_exchange_login_role CHECK (login_role IN ('BRAND','CREATOR'));

ALTER TABLE creator ADD COLUMN owner_user_id UUID REFERENCES "user"(id),
    ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    ADD COLUMN authorized_at TIMESTAMP,
    ADD CONSTRAINT ck_creator_authorization CHECK (
      (source_type='PUBLIC' AND owner_user_id IS NULL AND authorized_at IS NULL) OR
      (source_type='AUTHORIZED' AND owner_user_id IS NOT NULL AND authorized_at IS NOT NULL));
CREATE INDEX ix_creator_owner ON creator(owner_user_id);

CREATE TABLE creator_claim (
    id UUID PRIMARY KEY,
    creator_id UUID NOT NULL REFERENCES creator(id),
    claimant_id UUID NOT NULL REFERENCES "user"(id),
    evidence_urls JSONB NOT NULL,
    note VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED','REVOKED')),
    reviewer_id UUID REFERENCES "user"(id),
    reason VARCHAR(2000),
    reviewed_at TIMESTAMP,
    revoked_by_id UUID REFERENCES "user"(id),
    revoke_reason VARCHAR(2000),
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX ux_claim_pending_user_creator ON creator_claim(creator_id,claimant_id) WHERE status='PENDING';
CREATE UNIQUE INDEX ux_claim_approved_creator ON creator_claim(creator_id) WHERE status='APPROVED';
CREATE INDEX ix_claim_owner_status ON creator_claim(claimant_id,status,created_at);
CREATE INDEX ix_claim_status_created ON creator_claim(status,created_at);

CREATE TABLE creator_rate_card (
    id UUID PRIMARY KEY,
    claim_id UUID NOT NULL REFERENCES creator_claim(id),
    content_type VARCHAR(16) NOT NULL CHECK(content_type IN ('VIDEO','POST','STORY','LIVESTREAM')),
    price NUMERIC(19,2) NOT NULL CHECK(price>=0),
    currency VARCHAR(3) NOT NULL,
    note VARCHAR(1000),
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ux_rate_claim_type UNIQUE(claim_id,content_type)
);
