-- V49: Cloud storage metadata for avatars, campaign images, and post media
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
