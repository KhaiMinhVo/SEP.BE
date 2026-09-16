CREATE TABLE brands (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    business_name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX ix_brands_owner ON brands(owner_user_id);

CREATE TABLE brand_profiles (
    id UUID PRIMARY KEY,
    brand_id UUID NOT NULL UNIQUE REFERENCES brands(id) ON DELETE CASCADE,
    industry VARCHAR(120),
    categories TEXT[] NOT NULL DEFAULT '{}',
    website VARCHAR(500),
    location VARCHAR(150),
    target_markets TEXT[] NOT NULL DEFAULT '{}',
    target_audience TEXT[] NOT NULL DEFAULT '{}',
    brand_tone VARCHAR(150),
    preferred_platforms TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE brand_context_m4 (
    id UUID PRIMARY KEY,
    brand_id UUID NOT NULL UNIQUE REFERENCES brands(id) ON DELETE CASCADE,
    context_version INTEGER NOT NULL,
    profile_context JSONB NOT NULL,
    learned_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_brand_context_version CHECK (context_version > 0)
);

CREATE TABLE campaigns (
    id UUID PRIMARY KEY,
    brand_id UUID NOT NULL REFERENCES brands(id),
    name VARCHAR(200) NOT NULL,
    product_service VARCHAR(500) NOT NULL,
    objective VARCHAR(32) NOT NULL,
    target_audience TEXT[] NOT NULL DEFAULT '{}',
    content_type VARCHAR(100),
    start_date DATE,
    end_date DATE,
    primary_kpi VARCHAR(100),
    kpi_target NUMERIC(19,2),
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_campaign_timeline CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);

CREATE INDEX ix_campaigns_brand ON campaigns(brand_id);
CREATE INDEX ix_campaigns_brand_status ON campaigns(brand_id, status);

CREATE TABLE campaign_requirements (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL UNIQUE REFERENCES campaigns(id) ON DELETE CASCADE,
    platforms TEXT[] NOT NULL,
    niches TEXT[] NOT NULL DEFAULT '{}',
    creator_tiers TEXT[] NOT NULL DEFAULT '{}',
    locations TEXT[] NOT NULL DEFAULT '{}',
    follower_min BIGINT,
    follower_max BIGINT,
    budget_min NUMERIC(19,2),
    budget_max NUMERIC(19,2),
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_campaign_followers CHECK (follower_min IS NULL OR follower_min >= 0),
    CONSTRAINT ck_campaign_followers_range CHECK (follower_max IS NULL OR follower_min IS NULL OR follower_max >= follower_min),
    CONSTRAINT ck_campaign_budget CHECK (budget_min IS NULL OR budget_min >= 0),
    CONSTRAINT ck_campaign_budget_range CHECK (budget_max IS NULL OR budget_min IS NULL OR budget_max >= budget_min)
);

CREATE TABLE campaign_context_m3 (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    context_version INTEGER NOT NULL,
    context_data JSONB NOT NULL,
    active BOOLEAN NOT NULL,
    archived_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(campaign_id, context_version),
    CONSTRAINT ck_campaign_context_version CHECK (context_version > 0)
);

CREATE UNIQUE INDEX ux_campaign_context_one_active
    ON campaign_context_m3(campaign_id) WHERE active = TRUE;
