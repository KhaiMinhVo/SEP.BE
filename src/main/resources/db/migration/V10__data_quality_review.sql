ALTER TABLE public_creator_metric
    ADD COLUMN quality_reviewed_by UUID REFERENCES "user"(id),
    ADD COLUMN quality_reviewed_at TIMESTAMP,
    ADD CONSTRAINT ck_metric_quality_review CHECK (
        (quality_reviewed_by IS NULL AND quality_reviewed_at IS NULL) OR
        (quality_reviewed_by IS NOT NULL AND quality_reviewed_at IS NOT NULL));

CREATE INDEX ix_metric_creator_latest ON public_creator_metric(creator_id, collected_at DESC, id DESC);
CREATE INDEX ix_metric_quality_pending ON public_creator_metric(data_confidence, collected_at, id)
    WHERE creator_type='KOC' AND data_confidence < 0.7 AND quality_reviewed_at IS NULL;
