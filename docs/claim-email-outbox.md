# Claim invitation email: outbox and asynchronous delivery

Only Brand invitations to claim a channel use this outbox. Admin/Data Manager registration invitations, email-verification OTP and legacy password-setup OTP keep synchronous SMTP and their existing rollback behavior. Platform proof remains dev simulation; manual evidence review is unchanged. This feature does not add real platform OAuth, ownership transfer or public Creator registration.

## Request and response

Both existing POST `/api/v1/creators/{id}/invite` and `/api/v1/creators/invitations` return HTTP 200 after invitation and outbox commit. Response retains id, creatorId, email, status PENDING and expiresAt, and adds `emailStatus: "QUEUED"`. Queued does not mean delivered; an async worker may complete before the client receives that response. The raw token is never returned. The link remains `/claim-profile?token=...`, valid for seven days. A pending invitation still blocks another invitation for that channel even when its email job is FAILED.

After verified Creator login, the current Claim/manual approval process grants ownership and creates or reuses CreatorMaster at its existing point. Additional platform linking remains subject to the existing ownership/verification rules. Sending email alone never creates a Creator account, Master, ownership or tick.

New owner-Brand endpoints require effective MANAGE_OWN_SHORTLIST and role BRAND:

- GET `/api/v1/creators/invitations/{id}/email-delivery` returns `data.status`, `attempts` (all cycles), `nextAttemptAt`, `lastSentAt`, `canRetry`.
- POST `/api/v1/creators/invitations/{id}/retry-email` has no body. For an eligible FAILED job, HTTP 200 queues another five-attempt cycle with exactly the same recipient, token and original expiry. No new invitation is created.

Other Brands and missing invitations return 404; non-Brand/disabled permission 403; anonymous 401. Not retryable, invalid/consumed/expired or historical invitations return 409 CLAIM_EMAIL_NOT_RETRYABLE. Cooldown or rolling-hour limit returns 429 CLAIM_EMAIL_RATE_LIMITED. Retry requires 60 seconds since the last attempt and at most five successful manual retry requests in the preceding hour. Concurrent retry requests enqueue only once.

Historical invitations have no outbox: GET returns NOT_TRACKED, zero attempts and canRetry false. Their links still work under the existing rules, but there is no raw link to resend from a token hash. Migration does not invent jobs or send old invitations again.

Example delivery data inside the existing ApiResponse wrapper:

```json
{
  "status": "FAILED",
  "attempts": 5,
  "nextAttemptAt": null,
  "lastSentAt": null,
  "canRetry": true
}
```

Use the same Brand Bearer token to GET the status and POST retry-email without a body. A successful retry response reports QUEUED, the unchanged total attempts, the next scheduled time, and canRetry false while work is pending. Success/failure may change before the next status read. Render the latest server state rather than assuming that HTTP 200 means a delivered message.

## Delivery lifecycle and failure handling

Invitation and encrypted outbox INSERT share one transaction; enqueue failure rolls back both. An AFTER_COMMIT listener submits to a separate `@Async` bean. The executor has two threads and a queue of 100. Rejected submission never deletes the job. A ten-second scheduler also submits due jobs and recovers abandoned leases, including after restart or a missed after-commit notification.

Workers lock Creator Account, Invitation and job in that order, re-read state, and reserve a two-minute lease with a unique processing token. Attempts count at reservation time, including a crashed attempt. SMTP runs outside the transaction and locks. Finalization checks both current lease token and expiry; an old worker cannot complete a replacement worker's job.

Delivery states: QUEUED, PROCESSING, SENT, FAILED, CANCELLED, EXPIRED. Five attempts per cycle use delays of 15/30/60/120 seconds between attempts; scheduler timing/load may add delay. A successful SMTP return marks SENT; this means the mail server accepted the send, not that the recipient read or received it in their inbox. SMTP failure does not roll back the committed invitation. FAILED remains PENDING at invitation level and is recoverable via retry-email until expiry.

Before sending, require invitation PENDING and not expired, unchanged token hash, and an unowned channel. Invalid jobs are cancelled/expired without SMTP. A state change after preflight can still race with SMTP; a delivered link always remains subject to current Claim guards. Terminal SENT/CANCELLED/EXPIRED jobs erase payload. FAILED retains encrypted payload for manual retry, then the scheduler erases it at expiry. An unexpired in-flight lease is left alone until completion or recovery.

Database leases prevent concurrent workers deliberately sending one job twice. SMTP can accept a message but lose its acknowledgement, or a sender can outlive a lease; a later attempt can send the same email again. This is durable retry with possible duplicates, not exactly-once email delivery. Keep the same link for these retries.

## Configuration and deployment

Use existing Gmail SMTP: smtp.gmail.com, port 587, authentication, STARTTLS enabled/required, five-second connection/read/write timeouts, SMTP_USERNAME, SMTP_PASSWORD (App Password), SMTP_FROM, EMAIL_VERIFICATION_ENABLED and EMAIL_OTP_SECRET. No new SMTP account is needed.

New variables in `.env.example`:

- EMAIL_OUTBOX_ENCRYPTION_KEY: required Base64 encoding of 32 cryptographically random bytes; separate from OTP and OAuth secrets. Do not commit it or rotate it while recoverable jobs still need it.
- CLAIM_EMAIL_DISPATCH_ENABLED: true by default. False pauses automatic submission/sweeping; invitations can still be queued if SMTP/key configuration is valid. Tests turn it off to drive workers deterministically.

Generate the key locally in PowerShell when configuring a deployment:

```powershell
$claimEmailKeyBytes = New-Object byte[] 32
$claimEmailRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$claimEmailRandom.GetBytes($claimEmailKeyBytes)
$claimEmailRandom.Dispose()
[Convert]::ToBase64String($claimEmailKeyBytes)
```

Copy the output into your private environment configuration. Never paste keys, links or OTP into logs/reports. The invitation table contains only token hash; recipient/link in the outbox use authenticated AES-256-GCM with a fresh nonce. No sensitive payload or raw SMTP errors are exposed in APIs or worker logs.

Apply V16 on an authorized database only after configuring the key. It adds claim_email_outbox and claim_email_retry, preserves existing tables/data and performs no historical backfill. V1–V15 remain unchanged. Missing SMTP/key returns 503 before a new invitation is created. Retain the key securely for pending/failed jobs; a wrong key causes delivery failure rather than sending corrupted content.

Frontend should display queued/delivery status, query email-delivery as needed, and offer retry only when canRetry is true. For NOT_TRACKED, do not promise a resend. Observe FAILED jobs via the owner API and operational queries; application logs identify job IDs without recipient/link or SMTP detail.

## Tests

ClaimEmailCipherTest verifies strict key validation, roundtrip, random nonce, wrong key and tampering. ClaimEmailAsyncTest verifies the actual async executor and safe error logging. PostgreSQL suites validate atomic enqueue, after-commit dispatch/rollback, concurrency, lease recovery, retry/cooldown/rolling limits, cancellation/expiry, RBAC and V15→V16 preservation. SMTP is mocked and Clock fixed; no real Gmail or shared database is used. See backend-test-cases.md for actual run results.

Verified 2026-10-23: 23/23 new outbox cases passed; 58/58 migration/schema/SMTP follow-up passed; full regression 589/589 across 69 suites, zero failures/errors/skips. V16 ran only on disposable test databases. Actual `.env`, Bruno, shared database and running BE were not changed; no commit/push was performed.
