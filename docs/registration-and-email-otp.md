# Registration invitations and email OTP

All paths use `/api/v1`. Authentication is email/password; Google Login has been removed. A verified login email does not prove ownership of a social channel.

## Frontend flow

1. Home-page registration calls `POST /auth/register` with `email`, `password`, `fullName`. It always creates BRAND. Unknown fields, including `role`, return 400.
2. ADMIN/DATA_MANAGER calls `POST /data/creator-invitations` with `{"email":"creator@example.com"}`. Requires effective `INVITE_CREATOR`. Response 201 includes ID, email and expiration, never the raw token.
3. Mail links to configured frontend `/auth/creator/register?token=...`. Frontend calls `POST /auth/creator-invitations/resolve` with `{"token":"..."}` to display the fixed email.
4. Creator registration calls `POST /auth/creator/register` with `invitationToken`, `password`, `fullName`. The email and CREATOR role come from the invitation; sending `email` or `role` returns 400. A CreatorMaster is created without channel ownership.
5. Both roles receive OTP and call `POST /auth/verify-email` with `{"email":"creator@example.com","otp":"012345"}`. Then call `POST /auth/login` with email/password.
6. `POST /auth/resend-verification` takes `{"email":"..."}`. Unknown/already verified email receives a generic response. Frontend handles 429 and offers resend after 60 seconds.

Registration returns 201 with no tokens. Correct password before verification returns 403 `EMAIL_NOT_VERIFIED`; JWT/refresh cannot bypass verification. Existing accounts remain verified. Passwords require at least eight characters.

## Limits and errors

- Invitation: 48 hours, single use, minimum 60 seconds between sends, at most five sends per rolling hour/email. Successful replacement invalidates the old link. Existing account: 409, never a role conversion.
- OTP: six digits, ten minutes, single use, five incorrect attempts. Wrong/expired: 400; fifth incorrect attempt and exhausted limit: 429; already verified: 409. Invalid-attempt counters commit even on error.
- OTP sends: minimum 60 seconds, five/hour/account including registration; verification/password setup share the delivery budget. Successful resend invalidates the old code.
- Missing mail configuration or SMTP failure: 503. Direct SMTP runs in the transaction; failure rolls back account/invitation/code changes. Database and SMTP cannot provide atomic delivery: a mail accepted before a later database failure can contain an unusable code/link. Retry through the same APIs.
- No OTP, invitation token, password or email body is returned in API responses or written to audit/logs. Codes use HMAC-SHA256; invitation tokens use SHA-256 hashes.

## SMTP configuration

Configure deployment environment from `.env.example`; the application does not automatically load `.env` unless the launcher imports it.

```dotenv
EMAIL_VERIFICATION_ENABLED=true
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=your-sender@gmail.com
SMTP_PASSWORD=your-app-password
SMTP_FROM=your-sender@gmail.com
SMTP_AUTH=true
SMTP_STARTTLS_ENABLED=true
SMTP_STARTTLS_REQUIRED=true
EMAIL_OTP_SECRET=replace-with-at-least-32-random-characters
CREATOR_INVITATION_FRONTEND_BASE_URL=http://localhost:5173
```

Use an app password for Gmail SMTP. Connection/read/write timeouts are five seconds. Keep secrets outside Git. Real `.env` is not modified. Registration invitations and OTP remain synchronous; the separate Brand claim invitation flow now uses an [encrypted outbox and async worker](claim-email-outbox.md).

## Legacy accounts and channel claims

For BRAND/CREATOR with historical Google identity and no password: `POST /auth/password-setup/request` takes email, then `/auth/password-setup/confirm` takes email, otp and newPassword. Other accounts receive the generic request response and cannot use this as a password reset. Successful setup increments authVersion and revokes refresh sessions without issuing tokens. OTP purposes are isolated.

Manual Claim and review/revoke remain. A claimant must be ACTIVE CREATOR with verified email, independent of historical Google identity. Old claim invitation callbacks now require a signed-in verified Creator whose email matches the invitation; they never create an account or assign a fixed password.

`OAuthProofClient` remains a simulation, available only with profile dev, without prod, and `SOCIAL_PROOF_MOCK_ENABLED=true`. Otherwise provider proof returns 503. This is not real Facebook/Instagram/TikTok/YouTube verification. No real provider adapter is added.

## Database and verification

V13 adds email verification and OTP delivery storage; V14 adds separate `creator_registration_invitation` tables and password-setup OTP. Vy's `creator_invitation` table is the distinct channel-claim invitation and is preserved. V1–V14 migration files remain unchanged. V15 subsequently removes historical `auth_exchange_code` and retired Rate Card storage, while retaining identities, OTP, invitations and ownership; see [V15 cleanup](schema-cleanup-v15.md).

Run tests with Java 21, Maven and Docker. PostgreSQL tests use disposable pgvector Testcontainers and mocked SMTP/clock; they do not use the configured shared database. See `backend-test-cases.md` for the actual run result.

Brand channel-claim invitations, duplicate/ownership blocking and SMTP behavior: [MVP invitation flow](claim-invitations-mvp.md). Brand claim links do not register Creator accounts.
