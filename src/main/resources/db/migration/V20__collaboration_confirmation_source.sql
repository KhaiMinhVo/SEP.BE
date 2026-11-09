-- Migration V20: Add confirmation_source column to collaboration table for traceability of external vs platform agreements
ALTER TABLE collaboration ADD COLUMN IF NOT EXISTS confirmation_source VARCHAR(64) NOT NULL DEFAULT 'BRAND_RECORDED_EXTERNAL';
