-- Images stay in cloud storage; only metadata is persisted here.
CREATE TABLE image_asset (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL,
  purpose VARCHAR(50) NOT NULL,
  url VARCHAR(2048) NOT NULL,
  storage_key VARCHAR(500) NOT NULL,
  mime_type VARCHAR(100) NOT NULL,
  size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
  width INTEGER,
  height INTEGER,
  updated_at TIMESTAMP NOT NULL
);
CREATE INDEX image_asset_owner_purpose ON image_asset(owner_id, purpose);
ALTER TABLE campaign ALTER COLUMN image_url TYPE VARCHAR(2048);
ALTER TABLE creator ALTER COLUMN avatar_url TYPE VARCHAR(2048);
ALTER TABLE post ALTER COLUMN thumbnail_url TYPE VARCHAR(2048);
ALTER TABLE post ADD COLUMN thumbnail_updated_at TIMESTAMP;
ALTER TABLE evidence_asset ADD COLUMN storage_key VARCHAR(500);
ALTER TABLE evidence_asset ADD COLUMN mime_type VARCHAR(100);
ALTER TABLE evidence_asset ADD COLUMN size_bytes BIGINT;
ALTER TABLE evidence_asset ADD COLUMN image_updated_at TIMESTAMP;
ALTER TABLE evidence_asset ALTER COLUMN storage_url TYPE VARCHAR(2048);
ALTER TABLE brand_profile ADD COLUMN logo_url VARCHAR(2048);
ALTER TABLE brand_profile ADD COLUMN logo_storage_key VARCHAR(500);
ALTER TABLE brand_profile ADD COLUMN logo_mime_type VARCHAR(100);
ALTER TABLE brand_profile ADD COLUMN logo_size_bytes BIGINT;
ALTER TABLE brand_profile ADD COLUMN logo_updated_at TIMESTAMP;
ALTER TABLE brand_profile ADD COLUMN cover_image_url VARCHAR(2048);
ALTER TABLE brand_profile ADD COLUMN cover_image_storage_key VARCHAR(500);
ALTER TABLE brand_profile ADD COLUMN cover_image_mime_type VARCHAR(100);
ALTER TABLE brand_profile ADD COLUMN cover_image_size_bytes BIGINT;
ALTER TABLE brand_profile ADD COLUMN cover_image_updated_at TIMESTAMP;
