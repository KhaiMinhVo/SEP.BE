-- V7__creator_legacy_claim_and_rate_card.sql
-- 1. Bổ sung role CREATOR vào ràng buộc user
ALTER TABLE "user" DROP CONSTRAINT IF EXISTS ck_user_role;
ALTER TABLE "user" ADD CONSTRAINT ck_user_role CHECK (role IN ('BRAND','CREATOR','DATA_MANAGER','ADMIN'));

-- 2. Bổ sung login_role cho auth exchange code
ALTER TABLE auth_exchange_code ADD COLUMN IF NOT EXISTS login_role VARCHAR(20) NOT NULL DEFAULT 'BRAND';
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_exchange_login_role') THEN
        ALTER TABLE auth_exchange_code ADD CONSTRAINT ck_exchange_login_role CHECK (login_role IN ('BRAND','CREATOR'));
    END IF;
END $$;

-- 3. Cập nhật các trường xác thực trên creator (sẵn sàng cho claim)
ALTER TABLE creator ADD COLUMN IF NOT EXISTS source_type VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE creator ADD COLUMN IF NOT EXISTS authorized_at TIMESTAMP;

-- 4. Bảng lưu yêu cầu claim thủ công kèm bằng chứng
CREATE TABLE IF NOT EXISTS creator_claim (
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

CREATE UNIQUE INDEX IF NOT EXISTS ux_claim_pending_user_creator ON creator_claim(creator_id, claimant_id) WHERE status='PENDING';
CREATE UNIQUE INDEX IF NOT EXISTS ux_claim_approved_creator ON creator_claim(creator_id) WHERE status='APPROVED';
CREATE INDEX IF NOT EXISTS ix_claim_owner_status ON creator_claim(claimant_id, status, created_at);
CREATE INDEX IF NOT EXISTS ix_claim_status_created ON creator_claim(status, created_at);

-- 5. Bảng Rate Card do KOC tự khai báo
CREATE TABLE IF NOT EXISTS creator_rate_card (
    id UUID PRIMARY KEY,
    claim_id UUID NOT NULL REFERENCES creator_claim(id),
    content_type VARCHAR(16) NOT NULL CHECK(content_type IN ('VIDEO','POST','STORY','LIVESTREAM')),
    price NUMERIC(19,2) NOT NULL CHECK(price >= 0),
    currency VARCHAR(3) NOT NULL,
    note VARCHAR(1000),
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ux_rate_claim_type UNIQUE(claim_id, content_type)
);
