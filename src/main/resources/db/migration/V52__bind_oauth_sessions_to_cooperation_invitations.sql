ALTER TABLE meta_oauth_session
    ADD COLUMN cooperation_invitation_id UUID REFERENCES cooperation_invitation(id),
    ADD CONSTRAINT ck_meta_oauth_cooperation_target
        CHECK (cooperation_invitation_id IS NULL OR target_creator_id IS NOT NULL);

ALTER TABLE tiktok_oauth_session
    ADD COLUMN cooperation_invitation_id UUID REFERENCES cooperation_invitation(id),
    ADD CONSTRAINT ck_tiktok_oauth_cooperation_target
        CHECK (cooperation_invitation_id IS NULL OR target_creator_id IS NOT NULL);
