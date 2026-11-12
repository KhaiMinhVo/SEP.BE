# V15: retired schema cleanup

V15 permanently removes `auth_exchange_code`, `creator_rate_card`, and `public_creator_metric.url_profile` / `location`. No archive tables are created. V1–V14 remain unchanged; historical tests for V12/V14 explicitly stop at those versions.

Before dropping the metric columns, each missing Creator field is backfilled independently. Null or whitespace-only `creator.profile_url` and `creator.location` use the latest nonblank matching metric value ordered by `collected_at DESC, id DESC`. Existing nonblank Creator values are never overwritten; no candidate means the original blank value remains. Snapshot values are copied without trimming or combining fields from the same snapshot.

Ingestion still accepts `profileUrl` and `location` with the same request names. They are stored only on Creator. Creator profile, comparison and Campaign Discovery responses keep their fields, using current Creator values. Location filters join Creator. Remaining metric measurements, reviewer state and generated search_vector are preserved; search_vector does not reference either retired column.

The migration uses explicit DROP TABLE / DROP COLUMN without CASCADE. It retains user email verification, OTP attempts/consumption/delivery budgets, external identities and legacy password setup, both invitation domains, Claim, CreatorMaster/authorization, audit history, Collaboration agreed fee and Relationship fees. Audit records may reference retired Rate Card history and remain readable.

This removes old prices, Google login exchange codes and historical metric URL/location values. After applying V15, recovery requires an external backup. Backend no longer has the AuthExchangeCode entity/repository. Rate Card APIs remain removed.

Deployment must apply this schema change with the updated backend together; older binaries still expect removed metric columns. Back up the deployment database before applying. This change has only been tested on disposable PostgreSQL/pgvector containers: no shared database migration, .env/Bruno edit, BE restart, commit or push is part of implementation.

Verification is mapped in `backend-test-cases.md`, including fresh migration, V14 upgrade, per-field backfill, whitespace, UUID ties, absent history, preserved auth/ownership/audit/fees, ingestion/filter/discovery/profile/compare, and regression.

Verified 2026-10-23: targeted 100/100; full regression 527/527, 63 suites, zero failures/errors/skips.
