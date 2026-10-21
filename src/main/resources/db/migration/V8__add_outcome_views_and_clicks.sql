ALTER TABLE campaign_outcome
    ADD COLUMN views BIGINT,
    ADD COLUMN clicks BIGINT,
    ADD CONSTRAINT ck_outcome_views_nonnegative CHECK (views >= 0),
    ADD CONSTRAINT ck_outcome_clicks_nonnegative CHECK (clicks >= 0);
