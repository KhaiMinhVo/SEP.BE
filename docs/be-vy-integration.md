# Integrating be_vy with invited Creator registration

This integration retains CreatorMaster, discovery grouping, ingestion gatekeeping,
the security package refactor and the claim/link/conflict domain from `be_vy`.

## Account registration and ownership

- Public registration creates BRAND. Creator registration remains invitation-only at
  `/auth/creator/register`, with email OTP required before login/JWT/refresh.
- Registration creates an empty CreatorMaster in the same transaction. It does not
  grant ownership of any social channel.
- `/data/creator-invitations` continues to send registration emails through SMTP.
  Its `creator_invitation` table and historical tokens are unchanged.
- Vy's profile-claim invitations are a separate concept, stored in
  `creator_claim_invitation`, with distinct Spring/JPA names. They do not replace
  registration invitations or allow a new User to be created by a callback.
- Legacy manual claim approval finds/creates the claimant's Master, then grants the
  channel. Revoke removes both Master and legacy owner references.
- An existing account used by profile-claim resolution must be an ACTIVE,
  email-verified CREATOR; it must match the invitation email when supplied.
- Google Login/exchange endpoints, OAuth login handlers and their service are removed.
  Legacy Google identities remain only for first-time password setup.

## Provider integration status

`OAuthProofClient` from be_vy is a simulation, not an implementation of Meta/TikTok/
YouTube provider verification. It is disabled by default. It runs only with the `dev`
profile and `application.creator.oauth-simulation.enabled=true`; `prod` or `production`
always blocks it. Unconfigured calls fail with 503 `OAUTH_PROVIDER_UNAVAILABLE`.
Do not describe simulated acceptance tests as live provider verification.

The separate real YouTube connection implementation, its V15 migration, Bruno,
credentials, embedding validation fix and build/log files are not part of this commit.

## Database upgrade

- Previously applied V1–V14 are preserved without rewriting their SQL/checksums.
- V16 adds Master/provenance fields and backfills one Master per existing owner.
  It preserves the legacy `owner_user_id`, authorization date and all historical data.
- V17 adds the profile-claim domain, using a separate claim invitation table.
- V15 is reserved for the uncommitted local YouTube feature. On a database that already
  applied that V15, this published tree alone will report a missing migration during
  Flyway validation. Do not repair/delete Flyway history or replace V15 with a no-op.
  Deploy only after the original V15 is available in the intended build, or use a
  separate database for this branch. Any later integration must also handle the order
  of V15 relative to already-applied V16/V17.
- No shared database migrations or BE restart are performed by this integration.

## Verification

Automated tests use PostgreSQL/pgvector Testcontainers and mocked email/provider calls.
Coverage includes invited registration → OTP → login → manual claim → Master ownership,
forward migration preserving ownership and registration invitations, simulation
restrictions, existing RBAC/permissions, discovery and business regressions.

The actual final regression result is recorded in `backend-test-cases.md` after execution.
