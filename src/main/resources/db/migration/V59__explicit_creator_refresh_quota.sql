CREATE TABLE creator_refresh_request (
    id uuid PRIMARY KEY,
    brand_profile_id uuid NOT NULL REFERENCES brand_profile(id),
    campaign_id uuid NOT NULL REFERENCES campaign(id),
    creator_id uuid NOT NULL REFERENCES creator(id),
    subscription_id uuid NOT NULL REFERENCES subscription(id),
    platform varchar(32) NOT NULL,
    username varchar(255) NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN ('RESERVED','QUEUED','DISPATCH_FAILED')),
    requested_at timestamptz NOT NULL,
    dispatched_at timestamptz
);
CREATE INDEX ix_creator_refresh_recent ON creator_refresh_request(brand_profile_id,creator_id,requested_at DESC);
