-- Align the three business aggregates with ERD V1 without rewriting applied migrations.

ALTER TABLE users RENAME COLUMN id TO user_id;
ALTER TABLE users RENAME COLUMN password TO password_hash;
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMP;
ALTER TABLE users DROP COLUMN version;

ALTER TABLE brand_profiles RENAME COLUMN id TO brand_profile_id;
ALTER TABLE brand_profiles RENAME COLUMN owner_user_id TO user_id;
ALTER TABLE brand_profiles RENAME COLUMN categories TO product_categories;
ALTER TABLE brand_profiles RENAME COLUMN target_audience TO target_audiences;
ALTER TABLE brand_profiles ADD COLUMN description VARCHAR(1000);
ALTER TABLE brand_profiles DROP COLUMN status;
ALTER TABLE brand_profiles DROP COLUMN version;

ALTER TABLE campaigns RENAME COLUMN id TO campaign_id;
ALTER TABLE campaigns RENAME COLUMN target_audience TO target_audiences;
ALTER TABLE campaigns ADD COLUMN platforms TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE campaigns ADD COLUMN niches TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE campaigns ADD COLUMN locations TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE campaigns ADD COLUMN follower_min BIGINT;
ALTER TABLE campaigns ADD COLUMN follower_max BIGINT;
ALTER TABLE campaigns ADD COLUMN budget_min NUMERIC(19,2);
ALTER TABLE campaigns ADD COLUMN budget_max NUMERIC(19,2);

UPDATE campaigns c
SET platforms = cr.platforms,
    niches = cr.niches,
    locations = cr.locations,
    follower_min = cr.follower_min,
    follower_max = cr.follower_max,
    budget_min = cr.budget_min,
    budget_max = cr.budget_max
FROM campaign_requirements cr
WHERE cr.campaign_id = c.campaign_id;

ALTER TABLE campaigns
    ADD CONSTRAINT ck_campaign_followers CHECK (follower_min IS NULL OR follower_min >= 0),
    ADD CONSTRAINT ck_campaign_followers_range CHECK (follower_max IS NULL OR follower_min IS NULL OR follower_max >= follower_min),
    ADD CONSTRAINT ck_campaign_budget CHECK (budget_min IS NULL OR budget_min >= 0),
    ADD CONSTRAINT ck_campaign_budget_range CHECK (budget_max IS NULL OR budget_min IS NULL OR budget_max >= budget_min);

DROP TABLE campaign_requirements;

ALTER TABLE campaigns DROP COLUMN primary_kpi;
ALTER TABLE campaigns DROP COLUMN kpi_target;
ALTER TABLE campaigns DROP COLUMN version;
