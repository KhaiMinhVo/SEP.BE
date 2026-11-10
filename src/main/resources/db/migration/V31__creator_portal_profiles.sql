CREATE TABLE creator_portal_profile (
 id uuid PRIMARY KEY, master_id uuid NOT NULL UNIQUE REFERENCES creator_master(id),
 profile_revision bigint NOT NULL CHECK(profile_revision >= 1), data jsonb NOT NULL CHECK(jsonb_typeof(data)='object'),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 CHECK(data->'contacts'->'bookingEmail'->>'visibility' IN ('PRIVATE','BRANDS','CONFIRMED_PARTNERS')),
 CHECK(data->'contacts'->'zalo'->>'visibility' IN ('PRIVATE','BRANDS','CONFIRMED_PARTNERS')),
 CHECK(data->'contacts'->'managerContact'->>'visibility' IN ('PRIVATE','BRANDS','CONFIRMED_PARTNERS')));
CREATE TABLE creator_portfolio_item (
 id uuid PRIMARY KEY, profile_id uuid NOT NULL REFERENCES creator_portal_profile(id),
 title varchar(200) NOT NULL, url varchar(2000) NOT NULL, description varchar(1000),
 position integer NOT NULL CHECK(position BETWEEN 0 AND 19), UNIQUE(profile_id,position));
-- No historical profile, contact consent or verification is inferred.
