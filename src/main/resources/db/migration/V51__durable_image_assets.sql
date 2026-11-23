-- Cloud storage metadata for avatars, campaign images, and post media
ALTER TABLE creator ADD COLUMN IF NOT EXISTS avatar_storage_key VARCHAR(500);
ALTER TABLE creator ADD COLUMN IF NOT EXISTS avatar_mime_type VARCHAR(100);
ALTER TABLE creator ADD COLUMN IF NOT EXISTS avatar_size_bytes BIGINT;
ALTER TABLE creator ADD COLUMN IF NOT EXISTS avatar_updated_at TIMESTAMP;

ALTER TABLE campaign ADD COLUMN IF NOT EXISTS image_storage_key VARCHAR(500);
ALTER TABLE campaign ADD COLUMN IF NOT EXISTS image_mime_type VARCHAR(100);
ALTER TABLE campaign ADD COLUMN IF NOT EXISTS image_size_bytes BIGINT;
ALTER TABLE campaign ADD COLUMN IF NOT EXISTS image_updated_at TIMESTAMP;

ALTER TABLE post ADD COLUMN IF NOT EXISTS thumbnail_url VARCHAR(1000);
ALTER TABLE post ADD COLUMN IF NOT EXISTS thumbnail_storage_key VARCHAR(500);
ALTER TABLE post ADD COLUMN IF NOT EXISTS thumbnail_mime_type VARCHAR(100);
ALTER TABLE post ADD COLUMN IF NOT EXISTS thumbnail_size_bytes BIGINT;

-- Images stay in cloud storage; only metadata is persisted here.
CREATE TABLE IF NOT EXISTS image_asset (
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
CREATE INDEX IF NOT EXISTS image_asset_owner_purpose ON image_asset(owner_id, purpose);
ALTER TABLE campaign ALTER COLUMN image_url TYPE VARCHAR(2048);
ALTER TABLE creator ALTER COLUMN avatar_url TYPE VARCHAR(2048);
ALTER TABLE post ALTER COLUMN thumbnail_url TYPE VARCHAR(2048);
ALTER TABLE post ADD COLUMN IF NOT EXISTS thumbnail_updated_at TIMESTAMP;
ALTER TABLE evidence_asset ADD COLUMN IF NOT EXISTS storage_key VARCHAR(500);
ALTER TABLE evidence_asset ADD COLUMN IF NOT EXISTS mime_type VARCHAR(100);
ALTER TABLE evidence_asset ADD COLUMN IF NOT EXISTS size_bytes BIGINT;
ALTER TABLE evidence_asset ADD COLUMN IF NOT EXISTS image_updated_at TIMESTAMP;
ALTER TABLE evidence_asset ALTER COLUMN storage_url TYPE VARCHAR(2048);
ALTER TABLE brand_profile ADD COLUMN IF NOT EXISTS logo_url VARCHAR(2048);
ALTER TABLE brand_profile ADD COLUMN IF NOT EXISTS logo_storage_key VARCHAR(500);
ALTER TABLE brand_profile ADD COLUMN IF NOT EXISTS logo_mime_type VARCHAR(100);
ALTER TABLE brand_profile ADD COLUMN IF NOT EXISTS logo_size_bytes BIGINT;
ALTER TABLE brand_profile ADD COLUMN IF NOT EXISTS logo_updated_at TIMESTAMP;
ALTER TABLE brand_profile ADD COLUMN IF NOT EXISTS cover_image_url VARCHAR(2048);
ALTER TABLE brand_profile ADD COLUMN IF NOT EXISTS cover_image_storage_key VARCHAR(500);
ALTER TABLE brand_profile ADD COLUMN IF NOT EXISTS cover_image_mime_type VARCHAR(100);
ALTER TABLE brand_profile ADD COLUMN IF NOT EXISTS cover_image_size_bytes BIGINT;
ALTER TABLE brand_profile ADD COLUMN IF NOT EXISTS cover_image_updated_at TIMESTAMP;
