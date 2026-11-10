ALTER TABLE collaboration ADD COLUMN execution_source varchar(30) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE collaboration ADD COLUMN execution_revision bigint NOT NULL DEFAULT 1;
ALTER TABLE creator_review ADD COLUMN review_tags jsonb NOT NULL DEFAULT '[]';
ALTER TABLE creator_review ADD COLUMN accepted_outcome_revision bigint;
ALTER TABLE historical_campaign ADD COLUMN source_review_id uuid REFERENCES creator_review(id);
ALTER TABLE historical_campaign ADD COLUMN source_revision bigint;
ALTER TABLE historical_campaign ADD COLUMN provenance varchar(32) NOT NULL DEFAULT 'LEGACY_UNVERIFIED';
CREATE UNIQUE INDEX ux_history_review_revision ON historical_campaign(source_review_id,source_revision) WHERE source_review_id IS NOT NULL;
-- Preserve old evidence values and quality labels, but never infer crawler verification.
CREATE TABLE collaboration_deliverable (
 id uuid PRIMARY KEY, collaboration_id uuid NOT NULL REFERENCES collaboration(id), creator_id uuid NOT NULL REFERENCES creator(id),
 proposal_id uuid REFERENCES cooperation_proposal(id), content_type varchar(100) NOT NULL, due_at timestamptz NOT NULL,
 acceptance_checklist jsonb NOT NULL, max_revision_rounds integer NOT NULL CHECK(max_revision_rounds BETWEEN 0 AND 10),
 revision bigint NOT NULL DEFAULT 1, submission_revision bigint NOT NULL DEFAULT 0, revision_rounds integer NOT NULL DEFAULT 0,
 status varchar(30) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SUBMITTED','CHANGES_REQUESTED','APPROVED')),
 source varchar(30) NOT NULL, created_at timestamptz NOT NULL);
CREATE INDEX idx_execution_deliverable ON collaboration_deliverable(collaboration_id,id);
CREATE TABLE collaboration_submission (
 id uuid PRIMARY KEY, deliverable_id uuid NOT NULL REFERENCES collaboration_deliverable(id), revision bigint NOT NULL,
 url varchar(2000) NOT NULL, actor_id uuid NOT NULL REFERENCES "user"(id), source varchar(30) NOT NULL,
 source_description varchar(2000), created_at timestamptz NOT NULL, UNIQUE(deliverable_id,revision));
CREATE TABLE collaboration_content_review (
 id uuid PRIMARY KEY, deliverable_id uuid NOT NULL REFERENCES collaboration_deliverable(id), submission_revision bigint NOT NULL,
 decision varchar(30) NOT NULL CHECK(decision IN ('APPROVE','REQUEST_CHANGES')), checklist jsonb NOT NULL, reason varchar(2000),
 actor_id uuid NOT NULL REFERENCES "user"(id), created_at timestamptz NOT NULL, UNIQUE(deliverable_id,submission_revision));
CREATE TABLE collaboration_publication (
 id uuid PRIMARY KEY, deliverable_id uuid NOT NULL REFERENCES collaboration_deliverable(id), submission_revision bigint NOT NULL,
 url varchar(2000) NOT NULL, post_key varchar(2200) NOT NULL, early_publication boolean NOT NULL,
 exception_acknowledged boolean NOT NULL DEFAULT false, exception_reason varchar(2000),
 actor_id uuid NOT NULL REFERENCES "user"(id), created_at timestamptz NOT NULL,
 UNIQUE(deliverable_id,submission_revision));
CREATE TABLE collaboration_outcome_revision (
 id uuid PRIMARY KEY, collaboration_id uuid NOT NULL REFERENCES collaboration(id), revision bigint NOT NULL,
 payload jsonb NOT NULL, calculation jsonb NOT NULL, actor_id uuid NOT NULL REFERENCES "user"(id),
 source varchar(32) NOT NULL DEFAULT 'SELF_REPORTED', reason varchar(2000) NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(collaboration_id,revision));
CREATE TABLE collaboration_outcome_acceptance (
 id uuid PRIMARY KEY, collaboration_id uuid NOT NULL REFERENCES collaboration(id), outcome_revision bigint NOT NULL,
 actor_id uuid NOT NULL REFERENCES "user"(id), accepted_at timestamptz NOT NULL, reopened_at timestamptz, reopen_reason varchar(2000),
 FOREIGN KEY(collaboration_id,outcome_revision) REFERENCES collaboration_outcome_revision(collaboration_id,revision));
CREATE UNIQUE INDEX ux_active_outcome_acceptance ON collaboration_outcome_acceptance(collaboration_id) WHERE reopened_at IS NULL;
CREATE FUNCTION execution_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Execution history is immutable'; END $$;
CREATE TRIGGER immutable_submission BEFORE UPDATE OR DELETE ON collaboration_submission FOR EACH ROW EXECUTE FUNCTION execution_immutable();
CREATE TRIGGER immutable_content_review BEFORE UPDATE OR DELETE ON collaboration_content_review FOR EACH ROW EXECUTE FUNCTION execution_immutable();
CREATE TRIGGER immutable_outcome_revision BEFORE UPDATE OR DELETE ON collaboration_outcome_revision FOR EACH ROW EXECUTE FUNCTION execution_immutable();

-- Cross-row execution references must remain within the same collaboration and submitted version.
ALTER TABLE collaboration_deliverable ADD CONSTRAINT fk_execution_member FOREIGN KEY(collaboration_id,creator_id) REFERENCES collaboration_channel(collaboration_id,creator_id);
ALTER TABLE collaboration_content_review ADD CONSTRAINT fk_content_review_submission FOREIGN KEY(deliverable_id,submission_revision) REFERENCES collaboration_submission(deliverable_id,revision);
ALTER TABLE collaboration_publication ADD CONSTRAINT fk_publication_submission FOREIGN KEY(deliverable_id,submission_revision) REFERENCES collaboration_submission(deliverable_id,revision);
ALTER TABLE creator_review ADD CONSTRAINT fk_review_accepted_revision FOREIGN KEY(collaboration_id,accepted_outcome_revision) REFERENCES collaboration_outcome_revision(collaboration_id,revision);
ALTER TABLE collaboration_publication ADD COLUMN exception_acknowledged_by uuid REFERENCES "user"(id);
ALTER TABLE collaboration_publication ADD COLUMN exception_acknowledged_at timestamptz;
