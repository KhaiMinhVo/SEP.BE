-- Migration V19: Non-Financial Outcome Engagement Metrics (Flow 5 Phase 4)

-- 1. Add social engagement metrics (likes, comments, shares) to campaign_outcome
ALTER TABLE campaign_outcome
    ADD COLUMN IF NOT EXISTS likes BIGINT,
    ADD COLUMN IF NOT EXISTS comments BIGINT,
    ADD COLUMN IF NOT EXISTS shares BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_outcome_likes_nonnegative') THEN
        ALTER TABLE campaign_outcome ADD CONSTRAINT ck_outcome_likes_nonnegative CHECK (likes IS NULL OR likes >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_outcome_comments_nonnegative') THEN
        ALTER TABLE campaign_outcome ADD CONSTRAINT ck_outcome_comments_nonnegative CHECK (comments IS NULL OR comments >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_outcome_shares_nonnegative') THEN
        ALTER TABLE campaign_outcome ADD CONSTRAINT ck_outcome_shares_nonnegative CHECK (shares IS NULL OR shares >= 0);
    END IF;
END $$;
