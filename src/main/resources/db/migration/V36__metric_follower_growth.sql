-- Match the existing PublicCreatorMetric growth fields; historical values remain unknown.
ALTER TABLE public_creator_metric
    ADD COLUMN IF NOT EXISTS previous_followers BIGINT,
    ADD COLUMN IF NOT EXISTS follower_change_7d BIGINT,
    ADD COLUMN IF NOT EXISTS follower_growth_rate NUMERIC(7, 2);
