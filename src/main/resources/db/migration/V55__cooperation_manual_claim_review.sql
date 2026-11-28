ALTER TABLE creator_claim
    ADD COLUMN cooperation_invitation_id UUID REFERENCES cooperation_invitation(id),
    ADD COLUMN review_evidence_type VARCHAR(32),
    ADD COLUMN review_verification_note VARCHAR(2000);
