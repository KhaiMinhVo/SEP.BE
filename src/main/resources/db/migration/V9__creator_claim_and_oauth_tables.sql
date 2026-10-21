-- V9__creator_claim_and_oauth_tables.sql

-- 1. Lời mời xác minh hồ sơ (Bổ sung đầy đủ brand_id và invited_by)
CREATE TABLE IF NOT EXISTS creator_invitation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_creator_id UUID NOT NULL REFERENCES creator(id),
    campaign_id UUID REFERENCES campaign(id),
    brand_id UUID REFERENCES brand_profile(id),
    invited_by UUID NOT NULL REFERENCES "user"(id),
    invited_email VARCHAR(255) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, ACCEPTED, EXPIRED, CANCELLED
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_invitation_token ON creator_invitation(token_hash);
CREATE INDEX IF NOT EXISTS idx_invitation_brand ON creator_invitation(brand_id, status);

-- 2. Lưu định danh trả về từ OAuth Provider (Tránh lệch namespace Provider ID vs Public ID)
CREATE TABLE IF NOT EXISTS creator_provider_identity (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    creator_id UUID NOT NULL REFERENCES creator(id),
    provider VARCHAR(50) NOT NULL, -- TIKTOK, YOUTUBE, FACEBOOK, INSTAGRAM
    provider_user_id VARCHAR(255) NOT NULL,
    provider_username VARCHAR(255),
    raw_profile_data JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_provider_identity UNIQUE(provider, provider_user_id)
);
CREATE INDEX IF NOT EXISTS idx_provider_identity_creator ON creator_provider_identity(creator_id);

-- 3. Bằng chứng xác minh quyền kiểm soát tài khoản (Token được mã hóa an toàn AES-256-GCM)
CREATE TABLE IF NOT EXISTS creator_account_verification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    creator_id UUID NOT NULL REFERENCES creator(id),
    verified_by_user_id UUID NOT NULL REFERENCES "user"(id),
    verification_method VARCHAR(50) NOT NULL DEFAULT 'OAUTH_AUTHORIZATION',
    granted_scopes TEXT,
    access_token_encrypted TEXT,
    refresh_token_encrypted TEXT,
    token_expires_at TIMESTAMP,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, REVOKED, EXPIRED
    verified_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_account_verification_creator ON creator_account_verification(creator_id, status);

-- 4. Bảng xử lý ngoại lệ và xung đột liên kết (Dành cho Data Manager)
CREATE TABLE IF NOT EXISTS creator_identity_conflict (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conflict_type VARCHAR(64) NOT NULL, -- ACCOUNT_ALREADY_LINKED, NAMESPACE_MISMATCH, DUPLICATE_CLAIM
    source_account_id UUID REFERENCES creator(id),
    target_master_id UUID REFERENCES creator_master(id),
    conflict_details JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_MANAGER', -- PENDING_MANAGER, RESOLVED, REJECTED
    resolved_by UUID REFERENCES "user"(id),
    resolved_at TIMESTAMP,
    resolution_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 5. Lịch sử kiểm toán danh tính (Audit Trail cho bảo mật và tranh chấp)
CREATE TABLE IF NOT EXISTS creator_identity_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(64) NOT NULL, -- CLAIM_MASTER, LINK_ACCOUNT, UNLINK_ACCOUNT, REVOKE_ACCESS
    master_id UUID REFERENCES creator_master(id),
    account_id UUID REFERENCES creator(id),
    performed_by UUID NOT NULL REFERENCES "user"(id),
    details JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
