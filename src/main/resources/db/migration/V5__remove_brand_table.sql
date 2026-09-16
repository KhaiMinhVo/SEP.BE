ALTER TABLE brand_profiles
    ADD COLUMN owner_user_id UUID,
    ADD COLUMN business_name VARCHAR(200),
    ADD COLUMN status VARCHAR(20),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE brand_profiles bp
SET owner_user_id = b.owner_user_id,
    business_name = b.business_name,
    status = b.status
FROM brands b
WHERE bp.brand_id = b.id;

ALTER TABLE brand_profiles
    ALTER COLUMN owner_user_id SET NOT NULL,
    ALTER COLUMN business_name SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ADD CONSTRAINT ux_brand_profiles_owner UNIQUE (owner_user_id),
    ADD CONSTRAINT fk_brand_profiles_owner FOREIGN KEY (owner_user_id) REFERENCES users(id);

ALTER TABLE brand_context_m4 ADD COLUMN brand_profile_id UUID;
UPDATE brand_context_m4 m4
SET brand_profile_id = bp.id
FROM brand_profiles bp
WHERE m4.brand_id = bp.brand_id;
ALTER TABLE brand_context_m4
    ALTER COLUMN brand_profile_id SET NOT NULL,
    ADD CONSTRAINT ux_brand_context_profile UNIQUE (brand_profile_id),
    ADD CONSTRAINT fk_brand_context_profile FOREIGN KEY (brand_profile_id) REFERENCES brand_profiles(id) ON DELETE CASCADE;

ALTER TABLE campaigns ADD COLUMN brand_profile_id UUID;
UPDATE campaigns c
SET brand_profile_id = bp.id
FROM brand_profiles bp
WHERE c.brand_id = bp.brand_id;
ALTER TABLE campaigns
    ALTER COLUMN brand_profile_id SET NOT NULL,
    ADD CONSTRAINT fk_campaigns_brand_profile FOREIGN KEY (brand_profile_id) REFERENCES brand_profiles(id);

DROP INDEX ix_campaigns_brand_status;
DROP INDEX ix_campaigns_brand;
DROP INDEX ix_brands_owner;

ALTER TABLE brand_context_m4 DROP COLUMN brand_id;
ALTER TABLE campaigns DROP COLUMN brand_id;
ALTER TABLE brand_profiles DROP COLUMN brand_id;
DROP TABLE brands;

CREATE INDEX ix_campaigns_brand_profile ON campaigns(brand_profile_id);
CREATE INDEX ix_campaigns_profile_status ON campaigns(brand_profile_id, status);
