# Refunds and subscription cancellation

## Policy

Full refunds only, in the original currency and amount. Normal requests must be submitted within 24 hours of paidAt (inclusive), while the subscription is active and no campaign, AI recommendation or refresh quota has been used. Approval rechecks usage. Admins with MANAGE_SUBSCRIPTION may approve documented SYSTEM_ERROR or DUPLICATE_PAYMENT exceptions.

Payment remains PAID until money is confirmed refunded. Approval holds subscription access. Normal subscription cancellation remains effective at period end and does not refund money.

## Manual workflow

- POST /billing/payments/{id}/refund: owner submits {"reason":"Purchased by mistake"}.
- POST /admin/payments/{id}/refund: admin submits a request on behalf of the owner.
- POST /admin/payments/{id}/refund/approve: {"reason":"Verified unused"}; optional exceptionType SYSTEM_ERROR or DUPLICATE_PAYMENT. Set "manual":true to explicitly select manual-only handling (including legacy payments).
- POST /admin/payments/{id}/refund/reject: {"reason":"Already used"}.
- POST /admin/payments/{id}/refund/confirm: {"amount":100000,"currency":"VND","transferReference":"BANK-123"}.

Manual confirmation requires APPROVED or GATEWAY_FAILED, matching amount/currency and a bank transfer reference. Only confirm after the actual transfer. A PROCESSING gateway refund cannot be confirmed manually. REQUESTED -> REJECTED retains paid access; APPROVED holds access until refund processing is resolved.

## VNPAY automatic refunds

Set VNPAY_REFUND_ENABLED=true after configuring a merchant authorized to refund. The application fallback is false. The workspace .env and .env.example explicitly enable sandbox refunds; production merchant authorization is still required before using a production endpoint. VNPAY_QUERY_URL is also the refund endpoint (sandbox by default); configure the production endpoint explicitly when going live. VNPAY_QUERY_IP_ADDRESS must identify the calling server. Never point sandbox credentials at production or vice versa.

When enabled, approving a VNPAY payment automatically submits its full refund unless "manual":true was selected. Explicit manual-only approvals cannot later be submitted to the gateway. The same permission and policy apply. Non-VNPAY transactions retain manual processing.

- POST /admin/payments/{id}/refund/vnpay: submit an already approved, not previously submitted VNPAY refund.
- POST /admin/payments/{id}/refund/vnpay/query: query an uncertain/pending result, without resubmitting money movement.

Endpoints include the application's /api/v1 context path. HTTP 200 does not imply a completed refund; inspect refund.status.

States: APPROVED -> PROCESSING -> COMPLETED or GATEWAY_FAILED. RefundDetails includes gatewayRequestId, gatewayCode and gatewayTransactionNo. COMPLETE sets Payment=REFUNDED and Subscription=CANCELLED in the same transaction. Pending, timed-out, unverified or mismatched results keep Payment=PAID and the subscription held.

Before HTTP, persist one unique request ID and the original gateway checkout date. No DB transaction or row lock spans HTTP. A successful refund requires a verified signature, matching merchant/reference/amount, transaction type 02, response code 00, transaction status 00 and a refund transaction number. Type 01 is the original purchase, never evidence of a refund. Status 05/06 remains PROCESSING. Verified final errors (02/09 with response code 00, or rejection 95) become GATEWAY_FAILED, allowing manual fallback.

A scheduled job queries unresolved refunds once eligible after a minute; VNPAY_REFUND_RECONCILIATION_DELAY_MS controls polling frequency (default 60000). It never automatically resubmits a refund. Missing/ambiguous results stay PROCESSING and require merchant investigation if queries do not resolve them. A crash after persisting the claim but before sending HTTP is also uncertain: inspect VNPAY merchant records rather than generate a second refund or manually transfer money while an outcome is unknown. The system does not promise exactly-once processing across the network.

The endpoint currently permits one gateway submission per payment. Even after a confirmed rejection, use verified manual fallback rather than generating a second gateway refund ID. An already approved manual refund can still be confirmed manually before any gateway submission has been claimed; row locking serializes these choices.

Legacy VNPAY payments missing the original gatewayCreateDate cannot use automatic refunds; retain manual handling. Gateway amounts use VND x 100, at most 12 numeric digits; whole-VND plans therefore cap at 9,999,999,999 VND. Refunds return through the original transaction and do not require collecting a separate beneficiary account.

## Migration and reporting

Changes remain combined in V41, including PROCESSING/GATEWAY_FAILED states, request IDs, timestamps and reconciliation indexes. Do not edit already-applied production migration history blindly; reconcile the deployment migration plan separately. Existing refunded rows remain COMPLETED without inventing refund dates or bank references.

Payment list/statistics from/to filter the original COALESCE(paidAt, createdAt), inclusive, not refund completion time. Reports currently restrict money totals to VND. LocalDateTime keeps existing billing/Hibernate conventions.

## Validation

HTTP/signature tests use a local mock HTTP server. Database integration tests mock provider responses and never submit real refunds. A real VNPAY sandbox acceptance run and merchant refund authorization are still required before production use. This workspace contains backend API changes, not a frontend.

Official protocol: https://sandbox.vnpayment.vn/apis/docs/truy-van-hoan-tien/querydr%26refund.html
