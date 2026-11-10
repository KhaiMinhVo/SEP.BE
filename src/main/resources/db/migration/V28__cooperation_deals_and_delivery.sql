-- Retain cancelled agreements while allowing a new planned agreement on the same CRM channel.
ALTER TABLE collaboration DROP CONSTRAINT IF EXISTS collaboration_relationship_id_key;
ALTER TABLE collaboration DROP CONSTRAINT IF EXISTS ux_collaboration_relationship;
CREATE UNIQUE INDEX ux_collaboration_active_relationship ON collaboration(relationship_id) WHERE status <> 'CANCELLED';
ALTER TABLE relationship ADD COLUMN offered_fee numeric(19,2);
ALTER TABLE relationship ADD COLUMN counteroffer_fee numeric(19,2);
-- Cooperation invitations are separate from registration and claim invitations.
CREATE TABLE cooperation_deal (
 id uuid PRIMARY KEY, campaign_id uuid NOT NULL REFERENCES campaign(id), brand_user_id uuid NOT NULL REFERENCES "user"(id),
 master_id uuid REFERENCES creator_master(id), recipient_key varchar(80) NOT NULL,
 status varchar(32) NOT NULL CHECK(status IN ('CONTACTED','NEGOTIATING','CONFIRMED','DECLINED','CANCELLED')),
 revision bigint NOT NULL DEFAULT 1, source varchar(16) NOT NULL DEFAULT 'NEW',
 accepted_revision bigint, accepted_by uuid REFERENCES "user"(id), accepted_at timestamptz,
 collaboration_id uuid UNIQUE REFERENCES collaboration(id), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL);
CREATE UNIQUE INDEX ux_cooperation_recipient ON cooperation_deal(campaign_id,recipient_key) WHERE status <> 'CANCELLED' AND source <> 'LEGACY';
CREATE TABLE cooperation_member (
 deal_id uuid NOT NULL REFERENCES cooperation_deal(id), relationship_id uuid NOT NULL REFERENCES relationship(id),
 creator_id uuid NOT NULL REFERENCES creator(id), active boolean NOT NULL DEFAULT true,
 PRIMARY KEY(deal_id,relationship_id), UNIQUE(deal_id,creator_id));
CREATE UNIQUE INDEX ux_cooperation_member_active ON cooperation_member(relationship_id) WHERE active;
CREATE TABLE cooperation_proposal (
 id uuid PRIMARY KEY, deal_id uuid NOT NULL REFERENCES cooperation_deal(id), revision bigint NOT NULL,
 author_id uuid NOT NULL REFERENCES "user"(id), kind varchar(20) NOT NULL CHECK(kind IN ('OFFER','COUNTEROFFER')),
 fee numeric(19,2) NOT NULL CHECK(fee > 0), currency varchar(3) NOT NULL CHECK(currency='VND'),
 deliverables jsonb NOT NULL, terms text NOT NULL, created_at timestamptz NOT NULL, UNIQUE(deal_id,revision));
CREATE TABLE cooperation_history (
 id uuid PRIMARY KEY, deal_id uuid NOT NULL REFERENCES cooperation_deal(id), actor_id uuid REFERENCES "user"(id),
 action varchar(50) NOT NULL, revision bigint NOT NULL, metadata jsonb NOT NULL, created_at timestamptz NOT NULL);
CREATE TABLE cooperation_invitation (
 id uuid PRIMARY KEY, deal_id uuid NOT NULL REFERENCES cooperation_deal(id), proposal_revision bigint NOT NULL,
 token_hash varchar(64) NOT NULL UNIQUE, status varchar(20) NOT NULL, expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL);
CREATE TABLE cooperation_email_job (
 id uuid PRIMARY KEY, invitation_id uuid NOT NULL UNIQUE REFERENCES cooperation_invitation(id),
 status varchar(20) NOT NULL, attempts integer NOT NULL DEFAULT 0, cycle_attempts integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz, last_attempt_at timestamptz, sent_at timestamptz, lease_until timestamptz, processing_token uuid,
 payload text, manual_retries jsonb NOT NULL DEFAULT '[]');
CREATE INDEX idx_cooperation_email_due ON cooperation_email_job(status,next_attempt_at,lease_until);
CREATE TABLE cooperation_idempotency (
 actor_id uuid NOT NULL REFERENCES "user"(id), scope varchar(150) NOT NULL, key varchar(100) NOT NULL,
 request_hash varchar(64) NOT NULL, response jsonb NOT NULL, created_at timestamptz NOT NULL, PRIMARY KEY(actor_id,scope,key));
CREATE TABLE collaboration_channel (
 collaboration_id uuid NOT NULL REFERENCES collaboration(id), creator_id uuid NOT NULL REFERENCES creator(id),
 relationship_id uuid NOT NULL REFERENCES relationship(id), PRIMARY KEY(collaboration_id,creator_id));
INSERT INTO collaboration_channel SELECT id,creator_id,relationship_id FROM collaboration;
INSERT INTO cooperation_deal(id,campaign_id,brand_user_id,master_id,recipient_key,status,source,collaboration_id,created_at,updated_at)
 SELECT c.id,c.campaign_id,r.user_id,c.master_id,CASE WHEN c.master_id IS NULL THEN 'CHANNEL:'||c.creator_id ELSE 'MASTER:'||c.master_id END,
 CASE WHEN c.status='CANCELLED' THEN 'CANCELLED' ELSE 'CONFIRMED' END,'LEGACY',c.id,c.updated_at AT TIME ZONE 'UTC',c.updated_at AT TIME ZONE 'UTC'
 FROM collaboration c JOIN relationship r ON r.id=c.relationship_id;
INSERT INTO cooperation_member SELECT id,relationship_id,creator_id,status<>'CANCELLED' FROM collaboration;
UPDATE relationship r SET stage='CONFIRMED' WHERE EXISTS(SELECT 1 FROM collaboration c WHERE c.relationship_id=r.id AND c.status<>'CANCELLED');
UPDATE relationship SET stage='NEGOTIATING' WHERE stage='INTERESTED';
UPDATE relationship SET stage='DECLINED' WHERE stage='NOT_INTERESTED';
-- No historical proposal, acceptance, invitation or notification is fabricated.
CREATE FUNCTION cooperation_proposal_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Cooperation proposals are immutable'; END $$;
CREATE TRIGGER immutable_cooperation_proposal BEFORE UPDATE OR DELETE ON cooperation_proposal FOR EACH ROW EXECUTE FUNCTION cooperation_proposal_immutable();

CREATE INDEX idx_cooperation_campaign_created ON cooperation_deal(campaign_id,created_at DESC,id DESC);
CREATE INDEX idx_cooperation_master_updated ON cooperation_deal(master_id,updated_at DESC,id DESC);
CREATE INDEX idx_cooperation_member_creator ON cooperation_member(creator_id,deal_id);
CREATE INDEX idx_cooperation_notification_user ON notification(user_id,created_at DESC) WHERE type='COOPERATION_INVITATION';
