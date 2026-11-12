CREATE TABLE "user" (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_login_at TIMESTAMP
);

CREATE TABLE refresh_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    jti UUID NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    replaced_by_token_id UUID REFERENCES refresh_token(id),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE brand_profile (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES "user"(id),
    business_name VARCHAR(200) NOT NULL,
    industry VARCHAR(120),
    product_categories TEXT[] DEFAULT '{}',
    website VARCHAR(500),
    location VARCHAR(150),
    target_markets TEXT[] DEFAULT '{}',
    target_audiences TEXT[] DEFAULT '{}',
    brand_tone VARCHAR(150),
    preferred_platforms TEXT[] DEFAULT '{}',
    description VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE brand_context (
    id UUID PRIMARY KEY,
    brand_profile_id UUID NOT NULL UNIQUE REFERENCES brand_profile(id) ON DELETE CASCADE,
    context_version INTEGER NOT NULL,
    profile_context JSONB NOT NULL,
    learned_patterns JSONB NOT NULL,
    evidence_refs JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE campaign (
    id UUID PRIMARY KEY,
    brand_profile_id UUID NOT NULL REFERENCES brand_profile(id),
    name VARCHAR(200) NOT NULL,
    product_service VARCHAR(500) NOT NULL,
    objective VARCHAR(32) NOT NULL,
    target_audiences TEXT[] NOT NULL DEFAULT '{}',
    platforms TEXT[] NOT NULL DEFAULT '{}',
    niches TEXT[] NOT NULL DEFAULT '{}',
    locations TEXT[] NOT NULL DEFAULT '{}',
    follower_min BIGINT,
    follower_max BIGINT,
    content_type VARCHAR(100),
    budget_min NUMERIC(19,2),
    budget_max NUMERIC(19,2),
    start_date DATE,
    end_date DATE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE campaign_context (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES campaign(id) ON DELETE CASCADE,
    context_version INTEGER NOT NULL,
    context_data JSONB NOT NULL,
    active BOOLEAN NOT NULL,
    archived_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE platform (
    id UUID PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    provider_code VARCHAR(50) NOT NULL UNIQUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE creator (
    id UUID PRIMARY KEY,
    platform VARCHAR(50) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    user_name VARCHAR(255) NOT NULL,
    location VARCHAR(150),
    profile_url VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (platform, external_id)
);

CREATE TABLE public_creator_metric (
    id UUID PRIMARY KEY,
    creator_id UUID NOT NULL REFERENCES creator(id) ON DELETE CASCADE,
    followers BIGINT,
    avg_views NUMERIC(19,2),
    avg_likes NUMERIC(19,2),
    avg_comments NUMERIC(19,2),
    avg_shares NUMERIC(19,2),
    engagement_rate NUMERIC(7,4),
    niche VARCHAR(120),
    location VARCHAR(150),
    content_summary TEXT,
    collected_at TIMESTAMP NOT NULL,
    freshness_status VARCHAR(32) NOT NULL,
    data_confidence NUMERIC(5,4),
    contact VARCHAR(500),
    url_profile VARCHAR(1000),
    category VARCHAR(120)
);

CREATE TABLE shortlist_item (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES campaign(id),
    creator_id UUID NOT NULL REFERENCES creator(id),
    user_id UUID NOT NULL REFERENCES "user"(id),
    priority VARCHAR(16) NOT NULL,
    note TEXT,
    added_at TIMESTAMP NOT NULL
);

CREATE TABLE relationship (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES "user"(id),
    creator_id UUID NOT NULL REFERENCES creator(id),
    campaign_id UUID NOT NULL REFERENCES campaign(id),
    shortlist_item_id UUID REFERENCES shortlist_item(id),
    stage VARCHAR(32) NOT NULL,
    contact_method VARCHAR(100),
    quoted_fee NUMERIC(19,2),
    agreed_fee NUMERIC(19,2),
    currency VARCHAR(3),
    last_contact_at TIMESTAMP,
    notes TEXT,
    next_action VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE collaboration (
    id UUID PRIMARY KEY,
    relationship_id UUID NOT NULL UNIQUE REFERENCES relationship(id),
    campaign_id UUID NOT NULL REFERENCES campaign(id),
    creator_id UUID NOT NULL REFERENCES creator(id),
    agreed_fee NUMERIC(19,2),
    currency VARCHAR(3),
    status VARCHAR(32) NOT NULL,
    published_content_url VARCHAR(1000),
    start_at TIMESTAMP,
    completed_at TIMESTAMP,
    note TEXT,
    payment_status VARCHAR(32),
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE campaign_outcome (
    id UUID PRIMARY KEY,
    collaboration_id UUID NOT NULL UNIQUE REFERENCES collaboration(id),
    kpi_type VARCHAR(100),
    kpi_target NUMERIC(19,2),
    kpi_actual NUMERIC(19,2),
    result_note TEXT,
    recorded_at TIMESTAMP,
    actual_fee NUMERIC(19,2),
    currency VARCHAR(3),
    revenue NUMERIC(19,2),
    roi NUMERIC(12,4),
    evidence_quality VARCHAR(32),
    completed_at TIMESTAMP
);

CREATE TABLE evidence_asset (
    id UUID PRIMARY KEY,
    outcome_id UUID NOT NULL REFERENCES campaign_outcome(id),
    asset_type VARCHAR(50) NOT NULL,
    storage_url VARCHAR(1000) NOT NULL,
    source VARCHAR(100),
    uploaded_at TIMESTAMP NOT NULL
);

CREATE TABLE creator_review (
    id UUID PRIMARY KEY,
    outcome_id UUID NOT NULL UNIQUE REFERENCES campaign_outcome(id),
    collaboration_id UUID NOT NULL UNIQUE REFERENCES collaboration(id),
    reviewed_by_user_id UUID NOT NULL REFERENCES "user"(id),
    overall_rating SMALLINT NOT NULL,
    brand_fit_rating SMALLINT NOT NULL,
    would_collaborate_again BOOLEAN,
    note TEXT,
    reviewed_at TIMESTAMP NOT NULL
);

CREATE TABLE historical_campaign (
    id UUID PRIMARY KEY,
    creator_id UUID NOT NULL REFERENCES creator(id),
    collaboration_id UUID REFERENCES collaboration(id),
    outcome_id UUID REFERENCES campaign_outcome(id),
    evidence_type VARCHAR(32) NOT NULL,
    evidence_value JSONB,
    text_value TEXT,
    quality VARCHAR(32) NOT NULL,
    recorded_at TIMESTAMP NOT NULL
);

CREATE TABLE brand_pattern (
    id UUID PRIMARY KEY,
    brand_profile_id UUID NOT NULL REFERENCES brand_profile(id),
    platform VARCHAR(50),
    niche VARCHAR(120),
    average_successful_score NUMERIC(7,4),
    average_agreed_fee NUMERIC(19,2),
    preferred_engagement_rate NUMERIC(7,4),
    pattern_type VARCHAR(100) NOT NULL,
    pattern_value JSONB NOT NULL,
    confidence NUMERIC(5,4) NOT NULL,
    evidence_count INTEGER NOT NULL,
    learned_at TIMESTAMP NOT NULL
);

CREATE TABLE plan (
    id UUID PRIMARY KEY,
    plan_name VARCHAR(100) NOT NULL UNIQUE,
    price NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    duration_days INTEGER NOT NULL,
    campaign_quota INTEGER NOT NULL,
    ai_recommendation_quota INTEGER NOT NULL,
    refresh_quota INTEGER NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE subscription (
    id UUID PRIMARY KEY,
    brand_profile_id UUID NOT NULL REFERENCES brand_profile(id),
    plan_id UUID NOT NULL REFERENCES plan(id),
    start_date DATE NOT NULL,
    expiration_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL,
    used_campaign_quota INTEGER NOT NULL DEFAULT 0,
    used_ai_quota INTEGER NOT NULL DEFAULT 0,
    used_refresh_quota INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE payment (
    id UUID PRIMARY KEY,
    brand_profile_id UUID NOT NULL REFERENCES brand_profile(id),
    subscription_id UUID NOT NULL REFERENCES subscription(id),
    amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    payment_gateway VARCHAR(100) NOT NULL,
    transaction_code VARCHAR(255) UNIQUE,
    status VARCHAR(32) NOT NULL,
    paid_at TIMESTAMP,
    failure_reason TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES "user"(id),
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID,
    old_value JSONB,
    new_value JSONB,
    description TEXT,
    ip_address VARCHAR(64),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE notification (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES "user"(id),
    type VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    entity_type VARCHAR(100),
    entity_id UUID,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMP,
    expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

-- Insert sample Admin account (Password: Password123)
INSERT INTO "user" (id, email, password_hash, full_name, role, status, created_at, updated_at)
VALUES (
    '550e8400-e29b-41d4-a716-446655440000',
    'admin@influencermatch.com',
    '$2a$10$wY.O2g.B1761d1R.fG/j.O7NlXbY51FvA0/Z8B/UqQ9D0n4C.2K8a',
    'System Admin',
    'ADMIN',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);
