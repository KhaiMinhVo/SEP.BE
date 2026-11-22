CREATE TABLE collaboration_post_metric_collection (
    id uuid PRIMARY KEY,
    collaboration_id uuid NOT NULL REFERENCES collaboration(id),
    deliverable_id uuid NOT NULL REFERENCES collaboration_deliverable(id),
    publication_id uuid NOT NULL REFERENCES collaboration_publication(id),
    actor_id uuid NOT NULL REFERENCES "user"(id),
    request_key varchar(200) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    post_url text NOT NULL,
    requested_at timestamptz NOT NULL,
    collected_at timestamptz,
    metrics jsonb,
    raw_response jsonb,
    error_code varchar(80),
    UNIQUE (collaboration_id, actor_id, request_key)
);
CREATE INDEX collaboration_post_metric_history ON collaboration_post_metric_collection
    (publication_id, requested_at DESC);

ALTER TABLE collaboration ADD COLUMN management_mode varchar(24) NOT NULL DEFAULT 'COLLABORATIVE'
    CHECK (management_mode IN ('COLLABORATIVE','BRAND_MANAGED'));
ALTER TABLE collaboration ADD COLUMN completion_method varchar(24)
    CHECK (completion_method IN ('WITH_REVIEW','WITHOUT_REVIEW'));
ALTER TABLE collaboration ADD COLUMN terminated_at timestamptz;
ALTER TABLE collaboration ADD COLUMN termination_reason varchar(2000);
ALTER TABLE collaboration ADD COLUMN termination_evidence varchar(2000);
ALTER TABLE collaboration_submission ADD COLUMN received_at timestamptz;
ALTER TABLE collaboration_publication ADD COLUMN source varchar(30) NOT NULL DEFAULT 'CREATOR_PORTAL';
ALTER TABLE collaboration_publication ADD COLUMN source_description varchar(2000);
ALTER TABLE campaign ADD COLUMN closure_reason varchar(2000);
ALTER TABLE campaign ADD COLUMN closed_at timestamptz;
CREATE TABLE collaboration_exit_request (
    id uuid PRIMARY KEY,
    collaboration_id uuid NOT NULL REFERENCES collaboration(id),
    requested_by uuid NOT NULL REFERENCES "user"(id),
    reason varchar(2000) NOT NULL,
    evidence_url varchar(2000),
    created_at timestamptz NOT NULL,
    resolved_at timestamptz,
    resolved_by uuid REFERENCES "user"(id),
    resolution varchar(20) CHECK (resolution IN ('TERMINATED','DECLINED')),
    resolution_reason varchar(2000)
);
CREATE UNIQUE INDEX one_open_collaboration_exit ON collaboration_exit_request(collaboration_id)
    WHERE resolved_at IS NULL;

CREATE TABLE campaign_financial_summary_revision (
    campaign_id uuid NOT NULL REFERENCES campaign(id),
    revision bigint NOT NULL CHECK (revision > 0),
    request_key varchar(100) NOT NULL,
    request_hash char(64) NOT NULL,
    payload jsonb NOT NULL,
    calculation jsonb NOT NULL,
    actor_id uuid NOT NULL REFERENCES "user"(id),
    reason varchar(2000) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (campaign_id, revision),
    UNIQUE (campaign_id, actor_id, request_key)
);
