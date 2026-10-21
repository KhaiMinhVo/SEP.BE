-- V5__campaign_outcomes_and_data_quality.sql
-- 1. Bổ sung chỉ số views và clicks cho campaign_outcome
ALTER TABLE campaign_outcome
    ADD COLUMN IF NOT EXISTS views BIGINT,
    ADD COLUMN IF NOT EXISTS clicks BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_outcome_views_nonnegative') THEN
        ALTER TABLE campaign_outcome ADD CONSTRAINT ck_outcome_views_nonnegative CHECK (views >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_outcome_clicks_nonnegative') THEN
        ALTER TABLE campaign_outcome ADD CONSTRAINT ck_outcome_clicks_nonnegative CHECK (clicks >= 0);
    END IF;
END $$;

-- 2. Kiểm duyệt chất lượng dữ liệu KOC (Data Quality Review)
ALTER TABLE public_creator_metric
    ADD COLUMN IF NOT EXISTS quality_reviewed_by UUID REFERENCES "user"(id),
    ADD COLUMN IF NOT EXISTS quality_reviewed_at TIMESTAMP;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_metric_quality_review') THEN
        ALTER TABLE public_creator_metric ADD CONSTRAINT ck_metric_quality_review CHECK (
            (quality_reviewed_by IS NULL AND quality_reviewed_at IS NULL) OR
            (quality_reviewed_by IS NOT NULL AND quality_reviewed_at IS NOT NULL));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_metric_creator_latest ON public_creator_metric(creator_id, collected_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_metric_quality_pending ON public_creator_metric(data_confidence, collected_at, id)
    WHERE creator_type='KOC' AND data_confidence < 0.7 AND quality_reviewed_at IS NULL;
