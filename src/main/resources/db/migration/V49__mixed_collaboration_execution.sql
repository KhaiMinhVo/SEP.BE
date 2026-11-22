ALTER TABLE collaboration_publication ADD COLUMN received_at timestamptz;
ALTER TABLE collaboration
    ADD COLUMN termination_method varchar(20) CHECK (termination_method IN ('PORTAL', 'BRAND_EXTERNAL')),
    ADD COLUMN termination_agreed_at timestamptz,
    ADD COLUMN terminated_by uuid REFERENCES "user"(id);

ALTER TABLE collaboration_exit_request DROP CONSTRAINT collaboration_exit_request_resolution_check;
ALTER TABLE collaboration_exit_request ADD CONSTRAINT collaboration_exit_request_resolution_check
    CHECK (resolution IN ('TERMINATED', 'DECLINED', 'WITHDRAWN', 'TERMINATED_EXTERNAL'));
