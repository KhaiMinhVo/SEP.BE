ALTER TABLE cooperation_proposal
    ADD COLUMN kpi_type varchar(24),
    ADD COLUMN kpi_target bigint,
    ADD CONSTRAINT ck_proposal_kpi_commitment CHECK (
        (kpi_type IS NULL AND kpi_target IS NULL)
        OR (kpi_type IS NOT NULL AND kpi_target IS NOT NULL
            AND kpi_type IN ('VIEWS','CLICKS','LIKES','COMMENTS','SHARES') AND kpi_target > 0)
    );

ALTER TABLE collaboration
    ADD COLUMN committed_kpi_type varchar(24),
    ADD COLUMN committed_kpi_target bigint,
    ADD CONSTRAINT ck_collaboration_kpi_commitment CHECK (
        (committed_kpi_type IS NULL AND committed_kpi_target IS NULL)
        OR (committed_kpi_type IS NOT NULL AND committed_kpi_target IS NOT NULL
            AND committed_kpi_type IN ('VIEWS','CLICKS','LIKES','COMMENTS','SHARES')
            AND committed_kpi_target > 0)
    );

CREATE UNIQUE INDEX ux_historical_completion_observation
    ON historical_campaign(collaboration_id)
    WHERE collaboration_id IS NOT NULL AND evidence_type = 'COLLABORATION_COMPLETION';
