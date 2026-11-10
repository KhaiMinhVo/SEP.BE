ALTER TABLE creator_claim ADD COLUMN invitation_id uuid REFERENCES creator_invitation(id);
CREATE INDEX idx_claim_invitation ON creator_claim(invitation_id);
CREATE INDEX idx_master_catalog ON creator_master(created_at DESC,id DESC);
CREATE INDEX idx_creator_master_catalog ON creator(master_id,created_at DESC,id DESC);
CREATE INDEX idx_identity_events ON creator_identity_audit(created_at DESC,id DESC);
CREATE INDEX idx_identity_actor_events ON audit_log(entity_type,created_at DESC,id DESC);

-- A revoked OAuth proof supersedes older rows of the same method, not other methods.
-- Manual claims are independent evidence; no historical rows are inferred.
CREATE VIEW creator_effective_identity_evidence AS
SELECT creator_id,verified_by_user_id AS owner_id,verification_method AS method,status,verified_at,
       'ACCOUNT_VERIFICATION'::text AS source
FROM (
 SELECT v.*,row_number() OVER (PARTITION BY creator_id,verified_by_user_id,verification_method
 ORDER BY verified_at DESC NULLS LAST,(status<>'ACTIVE') DESC,id DESC) AS rn
 FROM creator_account_verification v
) v WHERE rn=1
UNION ALL
SELECT creator_id,claimant_id,'MANUAL_REVIEW',
       CASE WHEN status='APPROVED' THEN 'ACTIVE' ELSE 'REVOKED' END,
       CASE WHEN status='APPROVED' THEN reviewed_at ELSE revoked_at END,'CREATOR_CLAIM'
FROM creator_claim WHERE reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL
AND status IN ('APPROVED','REVOKED');

CREATE VIEW creator_identity_catalog_state AS
SELECT c.id AS creator_id,
 CASE WHEN c.source_type<>'AUTHORIZED' OR c.master_id IS NULL OR m.status<>'ACTIVE'
 OR u.role<>'CREATOR' OR u.status<>'ACTIVE' OR NOT u.email_verified THEN 'UNVERIFIED'
 WHEN EXISTS (SELECT 1 FROM creator_effective_identity_evidence e
 WHERE e.creator_id=c.id AND e.owner_id=m.user_id AND e.verified_at IS NOT NULL AND e.status='ACTIVE'
 AND ((e.source='CREATOR_CLAIM' AND e.method='MANUAL_REVIEW')
 OR (e.method='META_OAUTH' AND c.platform IN ('FACEBOOK','INSTAGRAM'))
 OR (e.method='INSTAGRAM_OAUTH' AND c.platform='INSTAGRAM')
 OR (e.method='TIKTOK_OAUTH_SANDBOX' AND c.platform='TIKTOK'))) THEN 'VERIFIED'
 ELSE COALESCE((SELECT CASE WHEN e.status<>'ACTIVE' THEN 'UNVERIFIED'
 WHEN e.method LIKE '%MOCK%' THEN 'SIMULATED' ELSE 'LEGACY_UNVERIFIED' END
 FROM creator_effective_identity_evidence e WHERE e.creator_id=c.id AND e.owner_id=m.user_id
 AND e.verified_at IS NOT NULL ORDER BY e.verified_at DESC,(e.status<>'ACTIVE') DESC,e.source DESC,e.method DESC LIMIT 1),
 'LEGACY_UNVERIFIED') END::text AS verification_status
FROM creator c LEFT JOIN creator_master m ON m.id=c.master_id LEFT JOIN "user" u ON u.id=m.user_id;
