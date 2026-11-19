CREATE TABLE collaboration_video_upload (
    id uuid PRIMARY KEY,
    deliverable_id uuid NOT NULL REFERENCES collaboration_deliverable(id),
    actor_id uuid NOT NULL REFERENCES "user"(id),
    expected_revision bigint NOT NULL CHECK (expected_revision > 0),
    file_name varchar(255) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 100000000),
    expires_at timestamptz NOT NULL
);

CREATE INDEX idx_collaboration_video_upload_expiry ON collaboration_video_upload(expires_at);

ALTER TABLE collaboration_submission ALTER COLUMN url DROP NOT NULL;
ALTER TABLE collaboration_submission
    ADD COLUMN video_upload_id uuid UNIQUE REFERENCES collaboration_video_upload(id),
    ADD CONSTRAINT chk_collaboration_submission_media
        CHECK (num_nonnulls(url, video_upload_id) = 1);
