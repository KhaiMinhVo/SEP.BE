# Brand claim invitations and ownership conflicts (MVP)

All paths below use `/api/v1`. This flow invites someone to claim a channel, not to register an account. Admin/Data Manager still sends a separate registration invitation; a Creator registers with password and email OTP before claiming. CreatorMaster creation on account registration is unchanged.

## API flow

BRAND with effective `MANAGE_OWN_SHORTLIST` calls either `POST /creators/{id}/invite` with `email` and optional `campaignId`, or `POST /creators/invitations` with additional required `creatorId`. Email is normalized. Brand must own its Brand Profile and any supplied campaign. Missing/inaccessible profile/campaign returns 404. Other roles return 403; anonymous callers 401.

HTTP 200 response keeps id, creatorId, email, status and expiresAt and adds emailStatus QUEUED. `rawToken` has been removed. A 32-byte random token is sent only in the email link, stored as SHA-256 hash in the invitation and encrypted inside the recoverable email outbox; never logged. Backend uses configured `application.auth.creator-invitation.frontend-base-url` (`CREATOR_INVITATION_FRONTEND_BASE_URL`, default http://localhost:5173) plus `/claim-profile?token=...`. The API does not accept a redirect URL. Claim invitations expire after 7 days, distinct from the 48-hour registration invitation.

Frontend opens `/claim-profile`, calls `GET /creators/claim/invitation/verify?token=...` for public channel details, then requires email-verified Creator login. Verify does not consume the invitation or change expired status. If no account exists, ask Admin/Data Manager for a registration invitation; a Brand claim token cannot be used for `/auth/creator/register`.

Manual Claim stays available via `POST /creators/{id}/claim` (evidenceUrls and note); no ownership is granted before Manager approval. If the channel has an effective pending invitation, only the invited Creator email can submit evidence. Approval consumes a matching pending invitation; review/revoke APIs remain active. Standalone manual claims without an effective invitation retain their existing behavior.

Existing dev callback `/creators/claim/oauth/{platform}/callback` requires JWT, active CREATOR, verified email, matching invitation email and matching channel platform. Locks and rechecks prevent replay, expiry and concurrent consumption. It still uses a simulated provider client only when explicitly enabled in dev without prod; no real Meta/TikTok/YouTube integration is added. OAuth remains channel proof, not Google account login.

## Blocking and transaction rules

- Account already has a Master or is AUTHORIZED: 409 `CREATOR_ALREADY_LINKED`; no new invitation, owner mutation or SMTP send.
- Any PENDING invitation with expiresAt > now for the channel, irrespective of Brand/email: 409 `CREATOR_INVITATION_PENDING`. Expired/used invitations do not block a new invitation. Existing pending duplicates are retained; any effective pending row still blocks sends.
- Callback repeated or invitation used: 409 `INVITATION_ALREADY_PROCESSED`; expired at or before now: 409 `INVITATION_EXPIRED`. No second Master, verification or successful claim audit.
- Channel already linked to another Master: link/callback/manual claim returns 409, no conflict queue, ownership transfer or token writes. A link to the same owner keeps its existing token-refresh behavior.
- Creator is locked with PESSIMISTIC_WRITE throughout checks and mutation; callback additionally reloads the locked invitation before consuming it. User lock serializes first Master creation in callbacks. Additional linking uses an identity advisory lock before reading the locked account, including the new-account path.
- Rejections due to ownership or duplicate invitations persist `REJECT_CREATOR_INVITATION_OR_OWNERSHIP` in audit_log with actor, channel, optional invitation ID, error reason and time. Response never identifies the other owner. A narrowly scoped CreatorOwnershipConflictException allows only this pre-mutation rejection audit to commit while HTTP remains 409. Audit failure rolls back and returns an error.

`POST /admin/creator-conflicts/{id}/resolve` is unregistered: authenticated callers get 404. Historical conflict rows and read API remain; no automatic transfer or cleanup occurs. No new conflict records are queued. Historical service-level tests of the old resolution method are not evidence that an API for transfer remains enabled.

## SMTP and frontend changes

Uses existing Gmail-compatible SMTP configuration and five-second timeouts, via JavaMailSender. Invitation and encrypted outbox commit together; AFTER_COMMIT submits asynchronous delivery and a scheduler recovers/retries jobs. Missing SMTP/key returns 503 before creating an invitation; enqueue failure rolls back. SMTP failure after commit keeps the invitation and retries delivery. See [outbox configuration, delivery status and manual retry APIs](claim-email-outbox.md). Registration invitations and OTP retain synchronous delivery. SMTP acknowledgements can be lost, so duplicate email is possible.

Frontend must stop reading rawToken, route `/claim-profile`, explain the separate registration invitation, handle 409 without repeatedly sending email, and avoid promising Manager transfer review. Keep passwords, OTPs and invitation links out of application logs.

## Verification and deployment

Claim callbacks lock User, then Creator Account, then Invitation with `PESSIMISTIC_WRITE` in one transaction. Each entity is refreshed under its lock; current User role/status/email verification, invitation email/platform/status/expiry and Account ownership are checked before business writes. The User lock also serializes claims of different channels by the same Creator so an existing Master is reused. Manual evidence submission and Manager approval remain unchanged.

Concurrency regression uses a ready latch and start gate with bounded waits (no sleep): same-invitation callbacks yield one 200 and one 409 `INVITATION_ALREADY_PROCESSED`; different-channel callbacks by one Creator both succeed using one Master. Replay preserves full Account, Master, Invitation, verification/token, provider identity and audit rows. Expiry at or before the fixed Clock returns 409 `INVITATION_EXPIRED` without provider calls or business writes. An accepted invitation is rejected identically without re-consuming it. Audit insertion failure rolls back all claim state. Provider/email are mocked; PostgreSQL transactions and locks are real Testcontainers behavior.

`ClaimInvitationPostgresIntegrationTest` uses pgvector/PostgreSQL Testcontainers with fake email/provider and deterministic Clock. It covers ownership/permission guards, invite races, callback races/replay/expiry, audit persistence/rollback, SMTP configuration rollback and post-commit SMTP failure, manual Claim email restriction, same-owner linking and retired endpoint 404. Other affected suites remain active. Mockito tests with Integration in their name are not PostgreSQL concurrency tests.

Outbox delivery adds V16; V1–V15 stay unchanged. No `.env` or Bruno changes, shared database access/migrations, backend restart, commit or push are included. Real provider integration and ownership transfer remain outside MVP. Actual run results are recorded in backend-test-cases.md.

Historical concurrency verification before outbox: 69/69 targeted and 566/566 full regression passed across 64 suites; 31 PostgreSQL invitation/conflict cases. Earlier MVP regression was 555/555. Current outbox results and executable case mappings are in backend-test-cases.md.
