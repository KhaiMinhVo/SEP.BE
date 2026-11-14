# InfluencerMatch Backend

Spring Boot 3.3, Java 21, PostgreSQL 16, Flyway, JWT authentication and RFC 9457 errors.

## Cách chạy chuẩn cho cả team (khuyến nghị)

Yêu cầu duy nhất: Docker Desktop đang chạy.

### 1. Chuẩn bị cấu hình

PowerShell:

```powershell
Copy-Item .env.example .env
```

macOS/Linux:

```bash
cp .env.example .env
```

`.env` chỉ dùng trên máy local và không được commit. Có thể bật tài khoản ADMIN phát triển trong file này:

```properties
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_EMAIL=poc-admin@example.local
BOOTSTRAP_ADMIN_PASSWORD=replace-with-a-strong-dev-password
BOOTSTRAP_ADMIN_FULL_NAME=PoC Administrator
```

Bootstrap ADMIN chỉ hoạt động với profile `dev`, mặc định tắt và không được dùng trong production.

### 2. Khởi động toàn bộ hệ thống

```powershell
docker compose up --build -d
docker compose ps
docker compose logs -f api
```

Compose sẽ tự khởi động PostgreSQL, chờ database healthy, chạy API và áp dụng các migration Flyway hiện có. Không cần cài Maven hoặc Java trên máy.

Các địa chỉ sau phải truy cập được:

- Health: http://localhost:8080/api/v1/actuator/health
- Swagger UI: http://localhost:8080/api/v1/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/api/v1/api-docs

Kiểm thử API tại `http://localhost:8080/api/v1`. Tham khảo Swagger UI để xem endpoint và request body; dùng Bearer token cho API yêu cầu đăng nhập.

Kiểm tra health bằng PowerShell:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/actuator/health
```

### 3. Dừng hệ thống

Giữ lại dữ liệu PostgreSQL:

```powershell
docker compose down
```

Xóa cả dữ liệu local và tạo database sạch ở lần chạy sau:

```powershell
docker compose down -v
```

Lệnh `down -v` làm mất toàn bộ dữ liệu PostgreSQL local.

## Chế độ phát triển backend nhanh

Chế độ này phù hợp khi cần sửa code và restart Spring Boot nhanh. Yêu cầu Java 21 và Maven 3.9+ trên máy.

```powershell
docker compose up -d postgres
mvn spring-boot:run
```

Nếu máy không có Maven, chạy backend trong Maven container:

```powershell
docker compose up -d postgres
docker run --rm -it `
  --name influencermatch-api-dev `
  --network sepbe_default `
  -p 8080:8080 `
  --env-file .env `
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://postgres:5432/influencermatch_dev" `
  -v "${PWD}:/workspace" `
  -v sep-be-m2:/root/.m2 `
  -w /workspace `
  maven:3.9.11-eclipse-temurin-21 `
  mvn spring-boot:run
```

Không chạy đồng thời service Compose `api` và container dev trên cùng port 8080. Nếu báo trùng tên container:

```powershell
docker rm -f influencermatch-api-dev
```

## Chạy kiểm thử

Máy có Maven:

```powershell
mvn test
```

Không có Maven:

```powershell
docker run --rm `
  -v "${PWD}:/workspace" `
  -v sep-be-m2:/root/.m2 `
  -w /workspace `
  maven:3.9.11-eclipse-temurin-21 `
  mvn test
```

Lưu ý: Testcontainers cần Docker daemon đang chạy. Nếu không có Docker, các test PostgreSQL sẽ thất bại, không tự bỏ qua. Nên chạy Maven trực tiếp trên máy có Docker Desktop.

## Các lỗi local thường gặp

### Sai mật khẩu PostgreSQL

Nếu `.env` đã đổi sau khi volume database được tạo, PostgreSQL vẫn giữ credential cũ. Với dữ liệu local không cần giữ:

```powershell
docker compose down -v
docker compose up --build -d
```

### Port 8080 hoặc tên container đang được sử dụng

```powershell
docker ps -a
docker compose down
docker rm -f influencermatch-api influencermatch-api-dev
```

Chỉ xóa các container local nêu trên; không xóa volume nếu cần giữ dữ liệu.

### Xem trạng thái và log

```powershell
docker compose ps
docker compose logs --tail 200 postgres
docker compose logs --tail 200 api
```

## Auth endpoints

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`
- `GET /api/v1/auth/policies` and `GET /api/v1/auth/csrf`
- `POST /api/v1/auth/logout-all`
- `POST /api/v1/auth/google/start`, `/auth/google/login`, `/auth/google/registration/complete`
- `POST /api/v1/auth/google/link/start` and `/auth/google/link/confirm`
- `POST /api/v1/brands/me/onboarding/complete`
- `POST /api/v1/auth/verify-email`
- `POST /api/v1/auth/resend-verification`
- `POST /api/v1/data/creator-invitations`
- `POST /api/v1/auth/creator/register`

## Registration and email OTP

Brand registration is public; Creator registration requires an ADMIN/DATA_MANAGER email invitation. Both verify OTP before password login. Optional Google Identity Services login is available only to Brand accounts; Google verifies email for new Google accounts.

## RBAC và phạm vi triển khai

Bốn role: `BRAND`, `CREATOR`, `DATA_MANAGER`, `ADMIN`. Danh mục permission và ma trận
mặc định nằm trong `security/Permission.java`, `security/RolePermissions.java`.
Admin có thể bật/tắt chức năng sẵn có của ba role còn lại; không cấp chéo role.
Quyền Admin giữ cố định. Quyền hiệu lực được nạp một lần mỗi request, không cache
liên request; token hiện tại nhận cấu hình mới từ request tiếp theo sau khi lưu.
Login/refresh/mock login trả `user.permissions`; `/auth/me` trả `permissions`.
Các trường hợp kiểm thử: [Backend Test Cases](docs/backend-test-cases.md).

Các API hiện có đã được bảo vệ:

- Auth profile; Admin User (xem, đổi status, cấp role).
- Brand Profile, Campaign và Creator Discovery: Brand chỉ thao tác dữ liệu của mình.
- Admin đọc Brand/Campaign để hỗ trợ bằng `VIEW_BRAND_SUPPORT_DATA`, không tạo/sửa/archive thay Brand.
- Plan: Brand đọc gói đang hoạt động; Admin quản lý bằng `MANAGE_PLAN`.
- `/subscriptions`: Brand quản lý subscription của mình; không tự kích hoạt gói trả phí.
- `/admin/subscriptions`: Admin quản lý subscription với audit, không cho Brand/Data Manager gọi.
- `/billing/subscribe`: Brand tạo checkout VNPay từ tài khoản trong JWT; callback xác minh chữ ký,
  merchant, số tiền và giao dịch trước khi thay đổi dữ liệu. Callback lặp được xử lý idempotent.
- `/admin/audit-logs`: Admin đọc audit có phân trang và lọc actor/action/entity/thời gian.
- `/creators/ingest`: chỉ service key, không chấp nhận JWT của bất kỳ role nào thay key.

Đường dẫn chuẩn là `/api/v1/plans`, `/api/v1/subscriptions` (context-path đã chứa `/api/v1`).
Alias đường dẫn billing cũ có prefix lặp vẫn được giữ để tương thích.

### Cấp role và thu hồi token

`PATCH /api/v1/admin/users/{id}/role` nhận `{ "role": "DATA_MANAGER", "reason": "..." }`,
yêu cầu `ASSIGN_USER_ROLE`. `PATCH .../{id}/status` nhận `status` và `reason` tùy chọn.
Brand đã có profile không thể chuyển sang role nội bộ; phải dùng tài khoản riêng.
Không được khóa, disable hoặc hạ role Admin hoạt động cuối cùng, kể cả request đồng thời.

Migration V4 thêm `auth_version`, constraint role và một bản ghi khóa chung `rbac_guard`.
Mỗi thay đổi role/status thực sự tăng version, revoke refresh token và ghi audit trong cùng transaction.
JWT kiểm tra role/status/version hiện tại từ database. JWT cũ thiếu claim coi là version 0;
sau khi version tăng, mở khóa không làm token cũ hợp lệ lại. Thao tác không đổi giá trị không tăng version.
Các API dùng JWT có `sid`, kiểm tra phiên trong database ở mỗi request; Google Login dành riêng cho Brand có thể bật qua GOOGLE_LOGIN_ENABLED; Google Login không cấp ownership kênh.

### Ingestion service key

Đặt `CREATOR_INGESTION_API_KEY` trong environment backend và header `X-Service-Key` của crawler.
Không cấu hình trả `503 INGESTION_NOT_CONFIGURED`; thiếu/sai key trả `401`.
Key chỉ dùng cho ingestion, không cấp quyền API quản trị hay dữ liệu riêng.
Docker Compose hiện truyền biến trong `.env` vào container API qua `env_file`.

### Chức năng và giới hạn

Shortlist/Relationship và Collaboration/Outcome/Review đã có API và test. Permission
Notification, Recommendation, History/Insights, Usage/Billing read và các tác vụ
vận hành dữ liệu chưa có API chỉ được khai báo trong enum; danh mục role-permissions
đánh dấu chúng `implemented=false`, `editable=false`.
Không tạo role mới hoặc cấp quyền chéo role. Chưa có entitlement/quota gate đầy đủ
cho mọi API hiện tại; RBAC không thay thế các kiểm tra này khi chúng được triển khai.
Không thể xem các permission đã khai báo là chức năng đã hoạt động.

### Creator Claim API change

Creator Rate Card endpoints have been removed. Creator profile and shortlist compare responses
no longer contain `rateCard`. Claim/ownership verification is unchanged. V12 preserves legacy
prices and audits while retiring the permission override.

Schema cleanup V15 removes retired exchange/Rate Card tables and duplicate metric profile fields.

Brand channel-claim invitations block duplicate invitations and owned channels. Brand claim links do not register Creator accounts.

Claim email uses durable outbox and async delivery, with status/retry APIs. Configure `EMAIL_OUTBOX_ENCRYPTION_KEY` before enabling invitations; V16 adds delivery tables without backfilling old invitations. Registration invitation and OTP emails remain synchronous.

## Facebook Page and Instagram Professional verification (Meta OAuth)

This connects an already signed-in, ACTIVE, email-verified Creator to an existing database channel.
It does not register/sign in a user. Manual Claim remains available. Facebook personal profiles,
Instagram personal accounts, real TikTok/YouTube adapters and metrics collection are outside this feature.

### Meta app and backend configuration

1. In the Meta developer dashboard, configure Facebook Login for Business with a **User Access Token**
   login configuration. Set the exact valid OAuth redirect URI, for example
   `http://localhost:8080/api/v1/integrations/meta/callback` locally, or the HTTPS backend callback on deployment.
2. Grant `pages_show_list` and `pages_read_engagement`; also grant `instagram_basic` for Instagram.
   This backend does not request publishing, messaging or advertising permissions. Instagram must be a
   Business/Creator account linked to a Facebook Page that the authorizing user can manage.
   For assets managed through a Business Portfolio, additionally grant `business_management` when needed
   to enumerate the Pages. In the local Facebook test on 2026-10-23, the three original scopes yielded no
   Pages; a new grant including `business_management` returned the managed Pages. This is not a global
   backend requirement: the required scope set stays minimal, and verification records the scopes
   actually granted. If the dashboard cannot edit a configuration, create another User Access Token
   configuration with the required permissions and use its configuration ID.
3. In Development mode, use an accepted app-role/test account with access to the test Page and linked
   Instagram account. Access for external Creators depends on Meta's access levels/App Review; backend
   implementation cannot approve the app. Select a Graph API version supported by your app explicitly.
4. Supply these environment variables using the placeholders in `.env.example`:

   ```dotenv
   META_OAUTH_ENABLED=true
   META_APP_ID=<app ID>
   META_APP_SECRET=<app secret>
   META_LOGIN_CONFIG_ID=<User Access Token login configuration ID>
   META_GRAPH_API_VERSION=<supported version such as vNN.0>
   META_REDIRECT_URI=http://localhost:8080/api/v1/integrations/meta/callback
   META_OAUTH_ENCRYPTION_KEY=<Base64 of 32 random bytes>
   ```

   Generate a separate key in PowerShell (do not reuse OTP or email outbox keys):

   ```powershell
   [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
   ```

V17 adds encrypted, temporary OAuth sessions; V1–V16 and existing ownership are unchanged.
Disabled/incomplete configuration returns `503 META_OAUTH_UNAVAILABLE`, with no mock fallback.
Legacy simulated Facebook/Instagram callbacks cannot verify accounts, even when dev mock is enabled.

Meta's official [Page account query](https://www.postman.com/meta/instagram/request/0vuw3vk/get-access-tokens-of-pages-you-manage)
documents the Page ID, tasks and linked `instagram_business_account` fields. This implementation uses
only the User Access Token, does not request Page tokens, and follows pagination cursors on a fixed Graph endpoint.

### Test with browser Swagger and Bruno

1. Register the Creator using the separate Admin/Data Manager registration invitation, verify OTP,
   then log in with email/password. Authorize Swagger with that Creator's access token.
2. Ensure the channel already exists with platform `FACEBOOK` or `INSTAGRAM`, `isStableId=true`,
   and `externalId` equal to the exact official Page ID or Instagram Professional ID. A Page ID is not
   its Instagram account ID. No channel is created or matched by username/email automatically.
3. **In browser Swagger**, call `POST /creators/portal/accounts/connect/meta/start`:

   ```json
   {"platform":"FACEBOOK","creatorId":"<existing channel UUID>"}
   ```

   `creatorId` is optional for general selection; `invitationToken` optionally binds an existing claim
   invitation. The caller needs effective `CLAIM_CREATOR_PROFILE`. The response includes `sessionId`,
   `authorizationUrl` and `expiresAt` (at most ten minutes).
4. Open `authorizationUrl` in the **same browser** and grant access with your Meta test account.
   Start sets an HttpOnly, SameSite=Lax cookie bound to this session; forwarding the URL or starting
   in Bruno then opening it in another cookie jar will be rejected. Future frontend requests must
   preserve credentials and use a compatible same-site backend deployment. The public callback returns
   JSON with session ID and `READY` only; it does not expose account choices/tokens or grant ownership.
5. In Bruno with the same Creator JWT, call
   `GET /creators/portal/accounts/connect/meta/sessions/{sessionId}`. Inspect sanitized choices:
   `AVAILABLE`, `ALREADY_LINKED`, `UNMATCHED` or `UNAVAILABLE`. Other users receive 404.
6. Confirm one account using `POST /creators/portal/accounts/connect/meta/sessions/{sessionId}/confirm`:

   ```json
   {"accountId":"<official Page or Instagram Professional ID returned in this session>"}
   ```

   The backend rechecks Meta outside database locks, then validates current User/authVersion/permission,
   stable channel ID, invitation email/expiry and ownership under locks. Success returns `AUTHORIZED`,
   reuses/creates the CreatorMaster, synchronizes relationships and records `META_OAUTH` evidence/audit.
   An invitation never creates a User; general selection cannot bypass another email's active invitation.
7. Repeat callback/confirm to check 409 replay protection. Another Master's channel returns 409 and
   durable rejection audit without transfer. Nonmatching IDs return `409 META_ACCOUNT_NOT_MATCHED`;
   use manual Claim instead. Changing permissions/status/authVersion before confirm blocks the operation.

Sessions and tokens are encrypted with AES-256-GCM. Verification retains official IDs, actual scopes
and verification time, not access/refresh tokens. Payload is cleared on completion/failure; a scheduled
sweep expires and clears unfinished sessions. Provider errors/timeouts never grant ownership; code
exchange is not retried automatically. No real Meta call is made by the automated tests.

### Instagram test through Facebook Login after connecting a Page

The same four endpoints already support `platform: INSTAGRAM`; no separate Instagram Login product,
callback, token store or schema migration is required. The backend reads the linked Professional node
from each managed Page. Pages without that node or management tasks produce no eligible Instagram
choice; they do not become Instagram accounts automatically. Username and display name are labels only.

The local manual test on 2026-10-25 confirmed `@iahkhnim_` with official Instagram ID
`17841450386037138`, linked to InfluencerMatch Page `1401052256422981`. Confirm returned AUTHORIZED
and reused the Facebook Master; Facebook records stayed unchanged and replay returned 409. This is
local test data, not a shared-database deployment. For another account, obtain its official ID anew
from Meta; never derive it from the handle, URL, Page ID or an earlier public Facebook URL.

1. Use the local-only Meta test database and verified Creator fixture. Keep the Facebook channel and its
   ownership in place. Do not use the shared database or change its IDs for this test.
2. In browser Swagger, start a fresh session with `{"platform":"INSTAGRAM"}` and no `creatorId`.
   Complete OAuth in that same browser. The public callback should report `READY`; this does not grant ownership.
3. Read the session using the Creator JWT. Find the Instagram choice returned by Meta, check its `pageId`
   against `1401052256422981`, and inspect the username as a human-readable confirmation. Its `accountId`
   is the official Instagram ID; it differs from `pageId`.
4. If `UNMATCHED`, prepare an explicit **local test fixture** with `platform=INSTAGRAM`, that official
   `externalId`, `isStableId=true`, and no owner. This is test preparation, not an auto-create feature of
   the OAuth API. Do not overwrite an existing crawler ID or create anything from a username alone.
5. Start a new session using `{"platform":"INSTAGRAM","creatorId":"<local Instagram channel UUID>"}`,
   finish OAuth, then confirm with `{"accountId":"<official Instagram ID>"}`. A session bound to a
   different channel cannot be repurposed for this one.
6. Expect `AUTHORIZED` and the same `masterId` as the Facebook connection. Verify the Facebook channel,
   verification and provider identity remain unchanged; Instagram has one active `META_OAUTH`
   verification and one provider identity, with the Page ID retained as metadata and no permanent token.
   Completed session payload is cleared. Repeating confirm returns 409 without duplicate evidence.

If the Instagram list is empty, check the actual Page–Professional account link and Meta asset grant;
do not insert a guessed ID or enable the legacy mock. For missing/declined `instagram_basic`, authorize
a fresh session with the correct configuration. Another Master's channel remains a 409 conflict.
Standard Access tests use accounts with accepted roles in the App; external Creator access requires
the relevant Meta approval/access level. Manual OAuth results and automated HTTP-mocked results are
recorded separately in the test-case document.

## Direct Instagram Login without Facebook

Both connection methods are supported. Direct Instagram Login verifies a Business/Creator account
without a Facebook account or Page. Creator registration invitation, email OTP and password login
remain unchanged; this OAuth flow never registers a User or issues an InfluencerMatch login token.
Personal Instagram accounts must be converted to Professional or use manual Claim.

### Separate Instagram app configuration

In Meta Dashboard, add/configure **Instagram API setup with Instagram Login**. Copy the **Instagram
App ID and Instagram App Secret** from that setup, not the Facebook Login configuration ID or assumed
Facebook credentials. Configure a valid HTTPS OAuth redirect and accepted Instagram test accounts/roles.
Request only `instagram_business_basic`; no Page, publishing, messaging or advertising scope is needed.
External accounts remain subject to Meta's access levels and App Review.

Configure the placeholders from `.env.example` when ready (disabled by default):

```dotenv
INSTAGRAM_OAUTH_ENABLED=true
INSTAGRAM_APP_ID=<Instagram App ID>
INSTAGRAM_APP_SECRET=<Instagram App Secret>
INSTAGRAM_GRAPH_API_VERSION=<explicit supported version>
INSTAGRAM_REDIRECT_URI=https://<backend-host>/api/v1/integrations/instagram/callback
INSTAGRAM_OAUTH_ENCRYPTION_KEY=<separate Base64 32-byte key>
```

Use the PowerShell key-generation command above, generating a new key distinct from Meta/OTP/outbox
keys. Direct Login works even with Facebook OAuth disabled or unconfigured. Missing configuration
returns `503 INSTAGRAM_OAUTH_UNAVAILABLE`; it never falls back to Facebook or the dev mock.
Do not enable or replace secrets in an existing environment until its corresponding app is configured.

### API and browser test

1. Apply V18 in the intended test database and run the updated backend. V18 adds `oauth_flow` to sessions;
   existing sessions default to `FACEBOOK_LOGIN` and keep their ciphertext/ownership unchanged.
2. Open Swagger through the **same HTTPS backend hostname** used by `INSTAGRAM_REDIRECT_URI`. If a
   test tunnel is needed, configure it explicitly; a Start request on localhost will not set a cookie
   for a different tunnel hostname. No frontend page or automatic tunnel is provided.
3. Log in the verified Creator, Authorize Swagger, then call
   `POST /api/v1/creators/portal/accounts/connect/instagram/start` with `{}` for discovery, or
   `{"creatorId":"<existing Instagram UUID>","invitationToken":"<optional claim invitation>"}`.
   The platform is fixed to INSTAGRAM by this route. `CLAIM_CREATOR_PROFILE` is required.
4. Open the returned `authorizationUrl` in the same browser and log in/consent on Instagram. The
   HttpOnly Secure SameSite=Lax cookie and hashed one-time state bind the browser and session. Public
   `GET /api/v1/integrations/instagram/callback` returns only session ID/status, never the token or profile.
5. Inspect the session through the existing authenticated
   `GET /api/v1/creators/portal/accounts/connect/meta/sessions/{sessionId}`. It reports
   `oauthFlow=INSTAGRAM_LOGIN`, platform INSTAGRAM and one sanitized account with no `pageId`.
6. Canonical ID is `user_id` from the authenticated Instagram account, independently of app-scoped
   `id`/subject or username. Only exact stable IDs already in the database can match. `UNMATCHED`
   requires manual Claim or an explicitly prepared local fixture, never auto-creation/ID rewriting.
7. Confirm through the existing `POST .../sessions/{sessionId}/confirm` using
   `{"accountId":"<canonical Instagram ID>"}`. The adapter rechecks Instagram before the transaction
   locks and fresh permission/authVersion/invitation/owner checks. Success returns AUTHORIZED, stores
   `INSTAGRAM_OAUTH` evidence and reuses the CreatorMaster and canonical INSTAGRAM identity.

Provider identity stays `INSTAGRAM` across both login methods. An account already verified through
Facebook is reused only if the canonical ID matches exactly; no username-based namespace mapping is
attempted. Other owners return 409 with audit, never transfer. Callback flow mixing and replay are
rejected, and audit failures roll back grant/session writes together.

Direct Login uses fixed Instagram hosts and no Facebook `/me/accounts` or `debug_token`. Short-lived
token validity is checked by profile access on confirm; no long-lived exchange, refresh job or token
maintenance is implemented. If the provider omits expiry, proof validity is bounded by the ten-minute
session rather than a guessed token lifetime. Payload uses the separate AES-256-GCM key and is cleared
on success/failure/expiry. Tokens, codes, app secrets and raw provider errors are not logged or returned.

The earlier live `@iahkhnim_` test used **Facebook Login**. It does not establish that Direct Instagram
Login was tested with a real app; see the test-case document for current automated/manual results.

## Local integration of be_vy (2026-10-26)

The merged workspace retains invited Creator registration/OTP and the real Facebook/Instagram
session flows while integrating be_vy `70453b2` Recommendation, Shortlist/CRM, reach analytics and
Collaboration changes. Existing Meta V17 and direct Instagram V18 remain unchanged; incoming Vy
migrations run as V19 (recommendation/shortlist), V20 (evidence) and V21 (outcome engagement).
Do not apply this history directly to a database that already ran Vy's original V17–V19 without
checking its migration history. This merge does not migrate or repair shared databases.

Legacy provider simulation remains explicitly dev-only. Facebook/Instagram cannot use legacy
callbacks; official TikTok uses the proof-only session APIs below. Ownership transfer
through `/admin/creator-conflicts/{id}/resolve` remains unexposed under the existing MVP policy.
TikTok/YouTube adapters are included from Vy but are not proven production-ready or live-tested by
this merge. Registration, email OTP and Claim outbox configuration remain independent.

## TikTok Sandbox OAuth — crawler author ID ownership (V22/V23)

Creators sign in using invited registration, password and email OTP. TikTok OAuth proves control
of the provider account; a fresh internal crawler read resolves its username to a stable author ID.
The callback links an existing channel or creates a minimal one. There is no extra Confirm endpoint.
The badge is InfluencerMatch verification, not TikTok's official badge. No Bio Challenge is used.
`externalId` stores the crawler author ID; `open_id` is kept in the app/environment-scoped binding.

Configure your environment using `.env.example` (the actual `.env` is not modified by this feature):

```dotenv
TIKTOK_SANDBOX_LOGIN_ENABLED=true
TIKTOK_SANDBOX_CLIENT_KEY=<Sandbox client key>
TIKTOK_SANDBOX_CLIENT_SECRET=<Sandbox client secret>
TIKTOK_SANDBOX_REDIRECT_URI=https://api.influencermatch.site/api/v1/integrations/tiktok/callback
TIKTOK_SANDBOX_SCOPE=user.info.basic,user.info.profile
CRAWLER_SERVICE_URL=http://localhost:8000/api/v1/crawl
```

In TikTok Developer Portal select/create a Sandbox, enable Login Kit Web, register the exact HTTPS
redirect, request both scopes and add the consenting account to Target users (up to ten). Use that
Sandbox's credentials. Disabled/missing/invalid configuration returns 503. The adapter uses fixed
TikTok HTTPS endpoints and ignores legacy configurable provider URLs.

For local testing use an HTTPS tunnel you control. Open Swagger/Start and callback on the same
hostname and browser to preserve the Secure HttpOnly SameSite=Lax cookie. This feature does not
create a tunnel or restart the backend for you.

1. Sign in as an ACTIVE, email-verified CREATOR with effective `CLAIM_CREATOR_PROFILE` permission.
2. POST `/api/v1/creators/portal/accounts/connect/tiktok/start` with `{}` and your JWT. Optional
   `creatorId` must identify an accessible TikTok channel. Optional `invitationToken` must be
   pending, unexpired and match your email; it is consumed only on a successful ownership grant.
3. Open `authorizationUrl` in the same browser and consent using the Sandbox target account.
4. Public GET `/api/v1/integrations/tiktok/callback` validates state/cookie, exchanges code and
   obtains TikTok User Info. It calls `POST {CRAWLER_SERVICE_URL}/tiktok/resolve-author` with
   `{"username":"<OAuth username>","auto_update_staging":false}`, without an expected ID.
   The crawler must return `status=success`, `platform=TIKTOK`, positive numeric `author_id` (1–20 digits),
   `id_type=TIKTOK_USER_ID`, actual `resolved_username` and UTC `observed_at`. Username normalization
   trims, removes one leading `@` and lowercases. Observations may be at most 90 seconds old or
   5 seconds ahead of the backend clock. Missing SSR uniqueId never falls back to the requested name.
5. Look up `(TIKTOK, author_id)` under the ingestion identity lock. An unowned/same-owner stable channel
   is linked/refreshed. If absent, create a minimal channel and reuse/create CreatorMaster atomically:

   ```json
   {"success":true,"data":{"sessionId":"<uuid>","status":"AUTHORIZED","creatorId":"<uuid>","masterId":"<uuid>"}}
   ```

   No metrics/posts/embedding are fabricated; later ingestion with that ID updates the same UUID.
   The follower/type gatekeeper remains in ingestion. Different owner, unstable stored ID or conflicting
   binding returns 409 and a rejection audit. No implicit merge, ID rewrite or transfer occurs.
6. A specified channel/invitation must match the author ID. Otherwise callback returns `READY` and
   GET `/api/v1/creators/portal/accounts/connect/tiktok/sessions/{id}` reports `TARGET_MISMATCH`,
   without creating a channel or consuming the invitation. Completed sessions retain only linked IDs;
   another user's session returns 404. Crawler HTTP/timeout/malformed/stale/mismatched results return
   `502 TIKTOK_IDENTITY_RESOLUTION_FAILED`. There is no username-only fallback or automatic retry.

V23 adds bindings scoped by `(SANDBOX, client key, open_id)` and prevents rebinding the identity or
channel within the same app. Legacy/mock identities are never imported as trusted bindings. Old
READY sessions do not gain ownership after upgrade: start new OAuth. There is no Sandbox-to-production
migration or automatic app-namespace conversion. This resolver change adds no migration. Existing Facebook/Instagram ownership is preserved.

State is hashed and consumed once before provider HTTP. HTTP runs outside DB locks: TikTok has five-second connect/read timeouts; crawler resolution has
five-second connect and 25-second read timeouts. No automatic retries occur. The callback rechecks User/authVersion/permissions,
invitation and ownership under locks before committing Master, binding, verification, shortlist/
relationship synchronization, invitation acceptance and audit atomically. Audit failures roll back
all ownership changes. Ingestion shares the TikTok gate and `TIKTOK:<author_id>` lock to prevent rename/insert races;
it does not acquire a User lock or perform HTTP while holding that gate.

No access/refresh token is persisted or returned. Unfinished sessions last ten minutes and expire
with profile cleanup on read or the minute sweep. Completed sessions retain only non-sensitive
result metadata. Replayed callback: 409 (expired state: 400); invalid/expired state: 400; wrong
browser/denied consent: 403; provider error: 502. Proxy logs should exclude OAuth query strings
and Authorization headers.

Official TikTok cannot use the legacy username-matching callback. Explicit dev MOCK_FALLBACK and
YouTube routing remain unchanged. Automated tests mock provider HTTP and use disposable PostgreSQL;
real Sandbox consent requires user configuration and has not been performed in this implementation.

References: [Login Kit Web](https://developers.tiktok.com/doc/login-kit-web),
[User Info](https://developers.tiktok.com/doc/tiktok-api-v2-get-user-info),
[Sandbox](https://developers.tiktok.com/doc/add-a-sandbox).


### Brand Google Login (Google Identity Services)

Google Login only supports BRAND. Enable `GOOGLE_LOGIN_ENABLED` and set `GOOGLE_LOGIN_CLIENT_ID` to the Web OAuth client ID; the GIS ID-token flow needs no client secret or backend Google redirect URI. Register the FE origin in Google's Authorized JavaScript origins. Provider signature, issuer, audience/authorized party, expiry, subject and verified email are checked; Google credentials are never persisted or logged.

Use the browser cookie and CSRF contract below for every Google POST:

1. `POST /api/v1/auth/google/start` returns `sessionId`, `clientId`, `nonce`, `expiresAt` and sets a browser-binding HttpOnly cookie. Pass the nonce to `google.accounts.id.initialize` and obtain a GIS credential.
2. `POST /api/v1/auth/google/login` accepts `{sessionId, credential}`. A previously linked, active verified Brand receives `status=AUTHENTICATED`, the access token/user response, and `IM_REFRESH` cookie.
3. A new identity with an unused email receives `status=REGISTRATION_REQUIRED`, `sessionId`, verified email, suggested `fullName` and `expiresAt`. No User, profile, refresh token or login session exists yet. Preserve the original cookie.
4. `POST /api/v1/auth/google/registration/complete` accepts `{sessionId, fullName, businessName, phoneNumber, policyAcceptance}`. Email/role cannot be selected. Successful completion atomically creates the verified Brand, initial Profile/Context, consent, identity and login session; response status is AUTHENTICATED. The original challenge still expires within ten minutes, including time spent filling the form. Reuse returns 409; a concurrently registered email returns 409.
5. If email already belongs to a password account, login returns 409 `GOOGLE_ACCOUNT_LINK_REQUIRED` (pending email OTP returns 403). Sign in with the password first. Then call `POST /auth/google/link/start` with `{password}` using Bearer JWT and CSRF, obtain a fresh GIS credential with its nonce, and call `POST /auth/google/link/confirm` with `{sessionId, credential}` from the same browser/login session. Google email must match. Linking never creates another User, bypasses OTP or changes role. Identity, challenge consumption and audit commit together.

Creator invitation/password/OTP and platform verification remain independent. Google-only Brands may use the existing first-password OTP setup endpoints; successful setup invalidates their sessions. No unlink endpoint is implemented.

### Authentication contract V25: FE integration and deployment

**Breaking change:** after applying V25 all users must sign in again. Old refresh tokens are revoked, each existing User's authVersion increases, and JWTs without a valid persisted `sid` are rejected. V1–V24 are unchanged. Historical accounts, identities, CreatorMaster, ownership and business data are preserved. Existing profiles become PENDING; historical consent or telephone numbers are not fabricated.

Before showing registration, fetch `GET /api/v1/auth/policies` for current `termsVersion` and `privacyVersion`. The server uses `TERMS_VERSION`/`PRIVACY_VERSION`; these identifiers do not publish or supply legal text. The team must publish the corresponding Terms/Privacy separately.

`POST /auth/register` requires:

```json
{
  "fullName": "Brand Owner",
  "email": "owner@example.com",
  "password": "strong-password",
  "businessName": "Example Brand",
  "phoneNumber": "0912345678",
  "policyAcceptance": {
    "acceptedTerms": true,
    "termsVersion": "terms-v1",
    "acceptedPrivacy": true,
    "privacyVersion": "privacy-v1"
  }
}
```

Both acceptance flags must be true. Missing/invalid fields return 400; outdated versions return 409 `POLICY_VERSION_CHANGED`, requiring a reload and renewed acceptance. Vietnamese numbers starting with 0 normalize to +84; international E.164 is supported, without telephone OTP. Passwords use BCrypt (minimum eight characters). Registration creates User, real initial Brand Profile, Context version 1, consent and email OTP atomically; SMTP failure rolls everything back. Response 201 contains no tokens. Verify email OTP, then log in.

`POST /auth/creator/register` still requires a valid Admin/Data Manager registration invitation and `{invitationToken, password, fullName}`; add the same `policyAcceptance` object. Email comes only from the invitation; role is CREATOR. No phone/business fields or channel ownership are invented. OTP remains mandatory.

Before authentication POSTs, call `GET /api/v1/auth/csrf` with browser credentials enabled. Keep its HttpOnly `IM_CSRF` cookie and send the returned `data.token` using `data.headerName` (`X-CSRF-TOKEN`). The endpoint returns the Spring Security 6.3 masked token; do not read cookies with JavaScript. This protects login, refresh, logout, logout-all, dev mock login and all Google POSTs; a missing/invalid token returns 403 `CSRF_INVALID`. Pure Bearer business APIs, provider callbacks, ingestion and VNPay keep their separate mechanisms. Use exact allowed CORS origins and credentials; no wildcard.

FE request example:

```javascript
const base = 'https://api.influencermatch.site/api/v1';
const csrf = (await (await fetch(base + '/auth/csrf', {
  credentials: 'include'
})).json()).data;
const response = await fetch(base + '/auth/login', {
  method: 'POST', credentials: 'include',
  headers: {'Content-Type': 'application/json', [csrf.headerName]: csrf.token},
  body: JSON.stringify({email, password})
});
```

`IM_REFRESH` is host-only, HttpOnly, path `/api/v1/auth`, SameSite=Lax. Deploy same-site HTTPS FE/BE and `AUTH_COOKIE_SECURE=true`; prod profile refuses insecure auth cookies. Local HTTP must explicitly set false. Reverse proxies must preserve HTTPS forwarding. Refresh tokens never appear in JSON (including Google/dev responses) and FE must not store one in localStorage. Bruno/API clients must retain the cookie jar and send the CSRF header; no Bruno files were changed.

- `POST /auth/login`: issues an independent seven-day absolute session. Access JWT lifetime is at most fifteen minutes and never exceeds session expiry.
- `POST /auth/refresh`: no JSON body required; reads only IM_REFRESH and rotates it under lock. Every successor expires at the original session end. Invalid/expired refresh returns 401 and clears the cookie; refresh replay revokes all of that User's sessions/tokens and persists that decision even when returning 401. FE should serialize simultaneous refresh requests.
- `POST /auth/logout`: refresh cookie + CSRF, no valid access JWT required, returns 204 and deletes cookie. Only this session/token family is revoked. Repeated logout remains harmless; the next request using its access token gets 401.
- `POST /auth/logout-all`: live Bearer JWT + CSRF; increments authVersion and revokes all sessions/refresh tokens, returning 204 and deleting cookie. Other devices must log in again.
- Role/status changes and password setup invalidate sessions atomically. Platform OAuth callbacks cannot complete against a revoked initiating session. Running requests/SSE streams are not interrupted; subsequent authentication, including SSE token authentication, checks the session.

Login and `/auth/me` add `brandProfileId` when present and `onboardingStatus` for BRAND (PENDING when no profile). Update the initial profile through ETag-protected PUT/PATCH `/brands/me` as described in the V26 contract below; PUT replaces all profile fields. Legacy accounts without a profile can still use `POST /brands`.

Call `POST /brands/me/onboarding/complete` with Brand JWT, current `If-Match` and effective `UPDATE_BRAND_PROFILE` after setting businessName, phoneNumber, industry, at least one productCategories item, targetAudiences item, brandTone and preferredPlatforms item. Missing fields return 400 with `fieldErrors`. Successful response includes COMPLETED/onboardingCompletedAt; repeats do not alter the timestamp. Completed profiles cannot be updated to remove required fields. This state guides navigation without introducing a global Brand business lock. Website, description/location remain optional. V26 adds the embedding and M4 behaviors described below; onboarding never waits for AI.

Automated checks use mock SMTP/provider and disposable PostgreSQL, not real consent or shared databases. See `docs/backend-test-cases.md` for actual run results.


## Brand Profile and M4 contract (V26)

New Brand registration still creates Profile + Context version 1 and PENDING onboarding. Legacy Brands without a profile can use POST /brands. V26 preserves existing context IDs and counters as the first available historical versions; missing earlier versions are not invented. Explicit context rows are now append-only, unique by Brand/version, and a database trigger rejects in-place updates. Legacy learned JSON/patterns and historical runs are retained.

### Revision and partial update

GET /brands/me and GET /brands/{id}, profile creation and successful writes return ETag `"brand:<profile UUID>:r<profileRevision>"`. The revision is separate from `m4Version`: it changes for a real profile change or first onboarding completion, while M4 versions change only for semantic preferences. Repeated identical requests keep both versions unchanged. Reordering a list or repeating its entries does not change its canonical semantic content.

PUT /brands/me, PUT /brands/{id}, PATCH /brands/me and POST /brands/me/onboarding/complete require the current If-Match. Missing header returns 428 PRECONDITION_REQUIRED; malformed header 400; stale or foreign revision 409 BRAND_REVISION_CONFLICT. On conflict, fetch/reconcile with the current profile rather than silently overwriting it. Exact CORS origins remain required; If-Match is allowed and ETag exposed.

PATCH changes supplied fields only. Omitted fields remain intact, null clears optional strings/lists, and [] clears a list. Unsupported fields and wrong JSON types return 400; ownership/userId cannot be patched. PUT retains full replacement semantics. Completed profiles cannot lose required onboarding fields through either API.

`positioning` (up to 1,000 characters) and `guidelines` (up to 4,000) are optional. No Brand budget field was added; budget remains a Campaign responsibility.

```javascript
const profileResponse = await fetch(base + '/brands/me', {headers: {Authorization: `Bearer ${accessToken}`}});
const revision = profileResponse.headers.get('ETag');
const updated = await fetch(base + '/brands/me', {
  method: 'PATCH',
  headers: {'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}`, 'If-Match': revision},
  body: JSON.stringify({positioning: 'Natural skincare', guidelines: 'Avoid unsupported medical claims'})
});
```

Only industry, productCategories, targetMarkets, targetAudiences, brandTone, preferredPlatforms, description, positioning and guidelines create a new M4 version. Name, phone, website, address and onboarding state affect profile revision without re-embedding. Profile/context/job changes commit together; enqueue failure rolls them back. Explicit context and embedding text exclude contact data/account IDs. The profile itself continues to hold authorized contact data; historical contexts are preserved as originally stored.

### Durable embedding

Set deployment variables when the existing Python /api/v1/ai/embed service is ready:

```dotenv
BRAND_EMBEDDING_ENABLED=true
BRAND_EMBEDDING_MODEL=bkai-foundation-models/vietnamese-bi-encoder
BRAND_EMBEDDING_MODEL_VERSION=m4-embed-v1
```

Default is disabled: saved jobs remain PENDING until enabled. `AI_SERVICE_URL` is the existing AI base/embed URL. Model version is an operator-controlled deployment revision, not a provider-attested checkpoint. Bump it when the provider embedding contract/model deployment changes. The scheduler ensures a configured-model job exists for each current context, including preserved contexts and a newly configured model revision.

A bounded two-thread executor wakes AFTER_COMMIT; a ten-second scheduler recovers missed events. Jobs are unique by context/model/version, use a two-minute lease and processing token, and make AI calls outside database transactions. Five attempts maximum per cycle with 15/30/60/120-second delays. Losing a lease prevents a stale worker from saving its result. Network errors and malformed vectors never become READY.

The strict provider response must have status success, the configured model name, dimensions 768, and 768 finite float-compatible components with a nonzero vector. The inherited client timeout is 5 seconds connect/15 seconds read. Errors are recorded through retry state and safe job/attempt logging, without raw provider payloads.

Brand responses include embeddingStatus PENDING/PROCESSING/READY/FAILED, embeddingModel, embeddingModelVersion and embeddingReadyAt when ready. They never expose the vector. POST /brands/me/context/embedding/retry requires Brand ownership and UPDATE_BRAND_PROFILE, only requeues a FAILED job for the current context/model, and returns 409 otherwise. Changing a profile does not discard older context/model results; they cannot replace the current context's embedding.

READY means a representation was stored. Brand vectors are not directly compared with Creator vectors lacking compatible model metadata; this release does not claim semantic SM4 is complete. AI failure does not roll back an already saved profile or block onboarding.

### Recommendation and learned evidence

Each run captures one immutable Brand Context and a cohort snapshot before scoring all candidates. New Campaign Context rows also record their Brand Context reference. RecommendationRun persists Brand Context ID/version, scoringPolicyVersion and the returned candidate breakdowns/weights/evidence IDs. Existing runs without those fields remain historical, not reconstructed or recalculated.

Campaign preferences take priority. Only missing campaign preferences are supplemented from the captured Brand context as soft matching/query text; Brand attributes never add hard discovery filters. Tone, positioning and guidelines supply textual context, not independently proven Creator attributes.

Policy `m4-evidence-v1` derives BRAND_FIT_BY_PLATFORM_NICHE aggregates from actual Brand-owned reviews of COMPLETED collaborations with matching Outcomes. Review IDs are deduplicated, and edits rebuild the aggregate rather than incrementing a stored count. Niche attribution uses the latest public snapshot at or before the review time, when available; otherwise same-platform evidence remains usable. Matching platform+niche is preferred, then same-platform evidence. No other platform or arbitrary first pattern is used.

A cohort requires five distinct reviews and confidence >= 0.75. Coverage heuristic: min(1, 0.50 + 0.05 * count); this is not an accuracy probability. Learned M4 is clamp(25 * (mean brandFitRating - 1), 0, 100), including low ratings. Unchanged aggregates retain their stored identity/time. Legacy patterns without evidence provenance and rejection feedback are retained but cannot establish a learned success score.

Original M2/M1/M4 weights are 0.50/0.30/0.20. M1 requires VERIFIED historical evidence and retains its previous formula when available. Missing M1/M4 returns null with availability/reason/evidence metadata, not fabricated 50/0. Available components receive originalWeight/sumAvailableWeights. Response breakdown includes originalWeights, effectiveWeights, evidence counts, M4 review IDs/confidence and scoringPolicyVersion. Coverage is distinct from the match score; these rules have not been empirically calibrated.

No changes to quota behavior, candidate limit or CreatorMaster deduplication are intended. Automated checks use mock AI and disposable PostgreSQL; production deployment, migration and real model verification are separate authorized actions.


## Campaign Brief and M3 contract (V27)

New Campaigns always start DRAFT. Only name is required for draft creation; supplied optional fields must still be valid. READY_FOR_DISCOVERY requires productService, brief, objective, platforms, startDate/endDate, totalBudget, creatorMode, targetCreatorCount and deliverables. Lifecycle remains DRAFT -> READY_FOR_DISCOVERY -> ACTIVE -> COMPLETED, or DRAFT -> ARCHIVED. Brief editing remains DRAFT-only. Saving/activating never runs Recommendation automatically.

### Campaign form and currency

- brief: maximum 10,000 characters; creatorMode: SINGLE or MULTI; SINGLE targetCreatorCount=1, MULTI >=2.
- deliverables: [{platform, contentType, quantity}], applying to **each Creator**. Quantity is positive, contentType max 100 characters, platform must be in Campaign platforms. These are planned requirements, not a Shortlist limit or automatic Collaboration allocation.
- totalBudget: positive total Campaign amount. currency: VND only. creatorFeeMin/creatorFeeMax: optional nonnegative per-Creator preference, ordered range. No division of total budget by target count, no hard fee constraint and no fee score/filter. feeAssessmentStatus=UNAVAILABLE: comparable deliverable/scope pricing is not yet available.
- Existing budgetMin/budgetMax remain historical read-only values, deprecated in Swagger. Requests containing those keys are rejected (including explicit null); no migration guesses their meaning. PUT preserves historical legacy amounts while replacing supported brief fields.
- Existing targetAudiences, contentType, niches, locations and follower ranges remain supported. TRAFFIC remains a valid objective. Autofill returns editable suggestions in the new form shape and never saves/activates a Campaign.

### ETag and request changes

GET /campaigns/{id}, create, update and status/archive responses return ETag: `"campaign:<id>:r<campaignRevision>"`. PUT /campaigns/{id}, PATCH /campaigns/{id}/status and PATCH /campaigns/{id}/archive require current If-Match. Missing header returns 428 PRECONDITION_REQUIRED, malformed 400, stale 409 CAMPAIGN_REVISION_CONFLICT. GET/reconcile after a conflict; do not blindly retry overwrites. PUT replaces the complete editable brief; omitted optional fields clear. No-op does not increment revision. Archiving an already archived Campaign with current ETag is idempotent. Existing credentials/ownership and CORS restrictions still apply.

Example request body (prefix /api/v1):

```json
{
  "name": "Serum launch",
  "productService": "Daily Serum",
  "brief": "Demonstrate the product in a short review. Avoid medical claims.",
  "objective": "AWARENESS",
  "platforms": ["TIKTOK"],
  "startDate": "2026-12-01",
  "endDate": "2026-12-31",
  "creatorMode": "MULTI",
  "targetCreatorCount": 3,
  "deliverables": [{"platform":"TIKTOK","contentType":"Review video","quantity":1}],
  "totalBudget": 15000000,
  "currency": "VND",
  "creatorFeeMin": 1000000,
  "creatorFeeMax": 3000000
}
```

### Frozen context and durable embedding

READY atomically saves a complete Campaign Context, its frozen Brand Context reference and a durable M3 job. Explicit Campaign values take precedence over Brand fallback. Later Brand updates do not rewrite the M3 input. Feedback creates another Context version containing exclusions/history while reusing the same embedding source; private feedback text is excluded from embedding. The database prevents editing historical context content; active/archive metadata can change.

Campaign response exposes contextVersion, embeddingContextId, embeddingStatus (NOT_REQUESTED before preparation; PENDING/PROCESSING/READY/FAILED for a tracked source), embeddingModel, embeddingModelVersion and embeddingReadyAt. No vector is exposed. POST /campaigns/{id}/context/embedding/retry requires ownership and UPDATE_CAMPAIGN; only the current FAILED source/model job can be retried.

Enable the durable worker on the deployed process only after AI is configured:

```dotenv
CAMPAIGN_EMBEDDING_ENABLED=true
CAMPAIGN_EMBEDDING_MODEL=bkai-foundation-models/vietnamese-bi-encoder
CAMPAIGN_EMBEDDING_MODEL_VERSION=m3-embed-v1
```

Default is false; queued jobs remain pending while disabled. modelVersion is an operator-defined deployment revision, not a provider checkpoint claim. Worker dispatches after commit with two threads/queue 100 and a 10-second recovery sweep. Lease=2 minutes, processing-token fencing, maximum five attempts with 15/30/60/120-second backoff; AI requests hold no DB transaction. Response must report success, matching model, dimensions 768 and a finite nonzero vector. Failure leaves the saved Campaign intact. Changing model/version makes a different job namespace; do not call old-model vectors current-model results.

### Recommendation source and quota

Creator discovery first filters existing DB records by Campaign context and UI filters, without calling AI. The Brand saves selected creators to its Campaign shortlist. POST /campaigns/{id}/ai-recommendations then scores SHORTLIST only (also the default for service calls). candidateSource=DISCOVERY returns 400; there is no catalogue fallback. Empty active shortlist returns 409 RECOMMENDATION_NO_CANDIDATES before scoring preparation or quota reservation. Empty eligible shortlist also returns 409 RECOMMENDATION_NO_CANDIDATES. Campaign not ready or embedding not READY returns 409 (CAMPAIGN_EMBEDDING_NOT_READY includes embeddingStatus). These failures do not reserve recommendation quota or create a Run. Recommendation reads persisted M3; no synchronous embedding request or zero-vector fallback remains.

Each Run stores candidateSource, Campaign Context/version, embedding source/job/model/version and the existing Brand/M4 evidence/score snapshot. Brand/M4 rules, quota amounts, candidate retrieval limits and CreatorMaster deduplication remain unchanged. Stored 768-dimensional M3 vectors still depend on the existing Creator embedding pipeline: legacy Creator vectors without model metadata are not independently proven compatible merely by having the same dimensions.

### Migration and legacy Campaigns

V27 leaves V1-V26 unchanged, preserves legacy budgets/contexts/runs and does not invent historical brief, deliverables, currency, target counts or embedding provenance. Old DRAFT Campaigns must supply the new READY fields. Existing READY/ACTIVE/COMPLETED Campaigns keep their lifecycle. The first new Recommendation without a tracked M3 source commits an idempotent LEGACY_SNAPSHOT from currently persisted facts and queues embedding; the response waits with 409. Missing fields remain missing. This preparation uses a short separate transaction so its job survives the waiting response; business/history Run data is not overwritten.

Only backend integration is supplied. FE must update form shape, ETag handling, source selection and pending/failed embedding display. No real .env, Bruno, shared DB migration or backend restart is part of this change.


## Shortlist batch, Compare and best-effort refresh

Shortlist stores **channels** by creatorId, not a single row per CreatorMaster. Multiple channels of one Master can be shortlisted/compared. Campaign targetCreatorCount does not cap Shortlist size. Adding/restoring does not book, reserve fees, create Relationship/Collaboration or send collaboration invitations. Outreach remains a separate explicit action.

### Add and batch

POST /api/v1/campaigns/{campaignId}/shortlist (alias /items) retains the single-add shape: creatorId, optional priority/note/source/recommendationRunId. creatorAccountId is a compatible alias; sending both with different values returns 400. A referenced RecommendationRun must exist and belong to the same Campaign/Brand; invalid references are rejected, not silently dropped.

POST /api/v1/campaigns/{campaignId}/shortlist/batch accepts:

```json
{"creatorIds":["<channel-uuid-1>","<channel-uuid-2>"],"priority":"HIGH","note":"Consider for launch","source":"MANUAL_DISCOVERY"}
```

Provide 1-100 distinct non-null UUIDs. Invalid batch structure/duplicate IDs reject the whole request before writes. Permission and Campaign ownership are checked before processing. Valid batches return HTTP 200 with ordered results, counts by action, and totalShortlistCount (excluding REMOVED):

- ADDED: a new item committed.
- ALREADY_EXISTS: active item returned unchanged, including existing priority/note/source. Use PATCH to edit metadata; repeating Add no longer overwrites it.
- RESTORED: REMOVED item becomes SAVED, preserving ID/addedAt/source history; supplied priority/note update during restore.
- INVALID: unknown channel or invalid RecommendationRun; other items continue.
- FAILED: an item transaction failed internally; response contains only a safe errorCode.

Each item has its own transaction. User -> Campaign -> item locks serialize Add with update/remove/batch-status, while the existing unique Campaign/channel constraint remains the final guard. A batch is not all-or-nothing: successful items remain if another fails. If infrastructure prevents completing the overall response, a 5xx may follow earlier commits; reread/retry safely rather than assuming nothing was saved.

### Snapshots and Compare

List/readiness/outreach read latest public snapshots by collectedAt DESC, id DESC; multi-row reads are batched. Shortlist DTO adds metricId, collectedAt, dataConfidence and freshnessStatus. Missing data stays nullable, never fabricated zero. masterId reflects the channel's current CreatorMaster, not an outdated copy on the item.

POST /campaigns/{campaignId}/shortlist/compare accepts 2-5 distinct creatorIds in display order. Every item must belong to the authorized Campaign/Brand and not be REMOVED. Invalid selections return 400; inaccessible Campaign returns 404. Response adds masterId, item status and feeAssessmentStatus=UNAVAILABLE. Metrics are public inputs for M2, not an already-computed M2/Four-Memory score. metrics remains explicitly null if absent. Compare is read-only and returns no contacts, price amounts, ranking or rateCard. The InfluencerMatch verification indicator is not a platform-issued verification badge.

Recommendation continues to take Campaign ID plus candidateSource=SHORTLIST and obtains eligible candidates server-side. Master deduplication and existing M1/M4/M3 gates remain unchanged. CRITIC/reference candidate pool is not implemented; current fixed/renormalized scoring policy remains in force.

### Crawl and SSE are best effort

ADDED/RESTORED registers an after-commit refresh event; ALREADY_EXISTS does not trigger another crawl. Add DTO reports refreshRequestStatus=PENDING_DISPATCH or NOT_REQUESTED. PENDING_DISPATCH is a local best-effort dispatch intention, not proof the crawler accepted or updated data.

A dedicated bounded executor (2 threads, queue 100) calls the fixed crawler /shortlist-trigger endpoint outside a database transaction. SSE emits CRAWL_REQUESTED only after a successful HTTP response, or CRAWL_REQUEST_FAILED on rejection/transport/executor failure. Failure does not roll back the committed item. No durable job, restart recovery, automatic retry or exactly-once delivery is promised.

Ingestion emits CRAWL_COMPLETED only after its transaction commits, using the saved metric ID, collection time and public metric values. Rollback emits no completion. SSE subscription checks effective MANAGE_OWN_SHORTLIST plus Campaign ownership, and messages contain no private contacts or raw provider errors. Streams remain per-instance, in-memory notifications: reread the Shortlist after reconnect and do not treat missed events as authoritative state.

No migration, outbox or new environment secret is required for this change. Existing V1-V27, real .env, Bruno and diagrams/SDD remain untouched.

Shortlist refreshRequestStatus describes the current Add response only; it is not persisted job state. GET/list does not report the last dispatch result. New addedAt values keep the existing local timestamp convention while using Clock for deterministic creation; restore preserves the original value.


## Cooperation CRM, grouped deals and budget-safe confirmation (V28)

Relationship remains a Campaign/channel CRM card. Deal groups one or more cards into **one agreement and one Collaboration**. Multiple channels require the same currently verified CreatorMaster. Unclaimed channels may be confirmed from external evidence only as a single-channel agreement; no User/Master/ownership is created. A second non-cancelled Deal for the same recipient in a Campaign is rejected. Historical agreements are not automatically merged.

### Brand API contract (prefix `/api/v1`)

- Existing Relationship create/detail/list remain; create is idempotent by Campaign/channel and never overwrites existing metadata. New writes use `offeredFee` (positive VND), not ambiguous legacy `quotedFee`; `counterofferFee` and `agreedFee` cannot be written through Relationship details.
- `POST /campaigns/{campaignId}/relationships/batch`: `{ "creatorIds": ["<uuid>"], "notes": "..." }`, 1-100 distinct non-null IDs, ordered SAVED/INVALID/FAILED results. Each item commits separately; resubmitting does not create duplicate CRM cards.
- `POST /campaigns/{campaignId}/deals`: `{ "relationshipIds": ["<uuid>", "<uuid>"] }`.
- `GET /campaigns/{campaignId}/deals`, `GET /campaigns/{campaignId}/deals/{dealId}`, and `GET .../{dealId}/history` read owned Deals; history returns immutable proposals and workflow events. List reads currently return up to 100 newest records.
- `POST .../{dealId}/proposals`: body below creates a new immutable revision. Omitted/invalid channels, non-positive fee, non-VND currency or missing deliverables for any member are rejected.
- `POST .../{dealId}/invitations`: `{ "expectedRevision": 2 }`, creates a cooperation invitation and returns delivery status separately from CRM state.
- `POST .../{dealId}/confirm`: confirm the current proposal using PORTAL acceptance or EXTERNAL evidence. Requires both Relationship and Collaboration permissions. Create/propose/send/confirm require `Idempotency-Key` (1-100 letters, digits, dot, underscore, colon or hyphen). Retrying the same key/content returns the original result; changed content returns 409. Use the returned `revision` for the next edit, not a hardcoded increment.
- `POST .../{dealId}/start`: `{ "expectedRevision": 3 }`, requires Campaign ACTIVE and Collaboration PLANNED.
- `POST .../{dealId}/cancel`: `{ "expectedRevision": 3, "reason": "..." }`; unconfirmed deals or confirmed PLANNED collaborations may be cancelled, never IN_PROGRESS/COMPLETED. Cancellation keeps history and releases commitment. Rebooking uses a new Deal; cancelled Collaborations remain stored.

Example proposal (fee is **for the entire Deal**, not per channel):

```json
{
  "expectedRevision": 1,
  "fee": 500000,
  "currency": "VND",
  "terms": "One review per channel with the agreed delivery schedule",
  "deliverables": [
    {"creatorId": "<channel-uuid>", "contentType": "Review", "quantity": 1}
  ]
}
```

Example external confirmation:

```json
{
  "expectedRevision": 2,
  "agreementSource": "EXTERNAL",
  "agreedAt": "2026-11-08T08:00:00Z",
  "evidenceSource": "EMAIL",
  "evidenceUrl": "https://your-authorized-storage.example/approval",
  "evidenceDescription": "Approval recorded by the Brand from the recipient"
}
```

External evidence requires a non-future agreement time, source, HTTPS URL without userinfo and description. The backend stores the Brand's attestation; it does not fetch the URL or certify its contents. PORTAL confirmation uses `{ "expectedRevision": 2, "agreementSource": "PORTAL" }`; the current Brand OFFER must have been accepted by the current active verified owner. A new proposal invalidates the previous acceptance and invitation; a Creator cannot self-accept their own counteroffer.

CRM progression is CONTACTED -> NEGOTIATING -> CONFIRMED, with DECLINED/CANCELLED branches. Do not PATCH stage to CONFIRMED, COLLABORATING or COMPLETED. Grouped cards are controlled by their Deal. CONFIRMED is distinct from Collaboration PLANNED/IN_PROGRESS/COMPLETED. Recording an Outcome keeps CRM CONFIRMED. Cash VND only in this version; barter, exclusivity, capacity and schedule checks are not implemented, and assessment fields return NOT_EVALUATED.

### Creator Portal and minimal invitation preview

- `GET /creators/portal/cooperation-deals`, `GET .../{id}`, `GET .../{id}/history`.
- `POST .../{id}/accept` or `/decline`: `{ "expectedRevision": 2 }`.
- `POST .../{id}/counteroffers`: proposal shape plus Idempotency-Key.
- Effective permission `RESPOND_OWN_COOPERATION` defaults to CREATOR; access also requires ACTIVE, verified email and current ownership of **all** member channels. Old owners and unrelated accounts cannot read private terms after a transfer. Collaboration detail/list responses include `channels`; primary creator/relationship fields remain for compatibility.
- `GET /creators/portal/notifications` reads the recipient's persisted cooperation notifications. `POST /creators/portal/notifications/{id}/read` marks only their own notification as read; invitation creation never marks it read.
- Frontend route is `${CREATOR_INVITATION_FRONTEND_BASE_URL}/cooperation-invitations?token=...`. `GET /cooperation-invitations/preview?token=...` is public and returns only Brand/Campaign names, invitation message and expiry. It does not return fee, email, private terms or ownership and cannot accept a Deal or log in a user.
- Tokens use 32 random bytes, only SHA-256 hashes persist outside encrypted delivery payload. TTL is 7 days. Manual-link retrieval requires the sending Brand at `GET /cooperation-invitations/{invitationId}/manual-link`; only valid MANUAL_REQUIRED invitations expose the link there. Never log or publish these URLs.

### Outreach and delivery

Existing Shortlist Outreach now requires both Shortlist and Relationship permissions and prepares cooperation Deals instead of Claim invitations. Multiple selected channels of one Master are grouped. Request may include `proposedFee`, VND `currency`, `outreachMessage` and `deliverables` using the proposal channel shape. Without complete proposal input it records CRM/Deal and returns NEEDS_PROPOSAL; it does not claim email was sent. Existing Deals return ALREADY_EXISTS without overwriting negotiated terms. Invalid/failed groups return safe per-item results and other groups may commit.

For owned channels, sending creates a real Portal notification and queues email to the owner account. Unclaimed channels use booking email from the newest stored public contact snapshot (collectedAt/id ordering), never display name or username. Without booking email, MANUAL_REQUIRED provides a manual cooperation link. These links do not replace Admin registration invitations or channel Claim; all those APIs remain separate.

Cooperation email uses a separate V28 job table and existing SMTP, TLS/timeouts and `EMAIL_OUTBOX_ENCRYPTION_KEY` cipher/executor. No new secret is needed. Email-bearing invitations require valid SMTP and encryption before enqueue; manual links require encryption. Invitation/notification/job commit together; enqueue errors roll back. SMTP runs outside transactions after commit and retries do not roll back the stored Deal/invitation.

- States: QUEUED/PROCESSING/SENT/FAILED, MANUAL_REQUIRED, CANCELLED/EXPIRED.
- Bounded shared mail executor: 2 threads, queue 100; recovery scan every 10 seconds.
- Lease 2 minutes with processing token; old/expired workers cannot overwrite a newer result.
- Five attempts per cycle, 15/30/60/120 second backoff. Replaced/expired/cancelled invitations do not dispatch; payload is erased at terminal state. FAILED retains ciphertext until retry or expiry.
- `GET /cooperation-invitations/{id}/email-delivery` returns safe state/attempts/timing/canRetry. `POST .../{id}/retry-email` retries a valid FAILED job, minimum 60 seconds since last attempt, max five successful manual retry requests/hour/invitation. Same token and expiry remain.
- Delivery is at-least-once: SMTP may accept a message before a response is lost. Do not promise exactly-once or equate QUEUED with SENT.

### Budget, compatibility and deployment

`GET /campaigns/{campaignId}/cooperation-budget` returns total/committed/remaining budget, confirmed Creator count and negotiating proposal total separately. Commitments count each non-cancelled PLANNED/IN_PROGRESS/COMPLETED Collaboration once, including historical ones; they are not actual cost or payments. SINGLE limits distinct recipients; MULTI target count is advisory and excess returns TARGET_CREATOR_COUNT_EXCEEDED. Concurrent confirmations serialize on Campaign and cannot overspend.

A legacy Campaign with missing totalBudget can use `POST /campaigns/{campaignId}/cooperation-budget` with `{ "totalBudget": 2000000, "currency": "VND" }` and current Campaign If-Match. Requires UPDATE_CAMPAIGN; only first initialization on READY_FOR_DISCOVERY/ACTIVE is allowed. It increments Campaign revision and records audit but does not rewrite historical M3 Context. Missing/non-VND legacy commitment amounts make budget UNAVAILABLE (null fields may be omitted by JSON settings) and block new confirmations; no conversion or zero fallback is applied.

The legacy `POST /campaigns/{id}/collaborations` no longer creates from CONTACTED directly. Supply `relationshipId`, `dealId`, `expectedRevision`, agreement source/evidence and Idempotency-Key; optional `agreedFee/currency` must match the proposal. It delegates to the same confirmation transaction and returns PLANNED, never implicitly starts execution. Outcome/Review remain one set per Collaboration; existing primary-channel metadata is retained for learned scoring rather than multiplying evidence per member channel.

V28 backfills historical Collaborations as one-channel LEGACY Deals without fabricating consent/proposals/invitations or changing fees/status/IDs. It preserves existing CRM history, maps legacy INTERESTED to NEGOTIATING and NOT_INTERESTED to DECLINED, and uses confirmed agreements as evidence for CONFIRMED. The old one-Collaboration-per-Relationship constraint becomes an active-only unique index so cancellation can retain history and allow a later agreement. V1-V27 remain unchanged. Apply V28 through normal controlled deployment; this implementation did not migrate a shared database or restart BE. Frontend must adopt Deal revision/idempotency and explicit start before rollout.


Integration details: Relationship responses include current `masterId` and `dealId/dealStatus/collaborationId` for grouping. Use Deal-level offer/counteroffer/agreed fee for grouped cards; do not sum the same Deal fee across its channel cards. The existing `CLAIM_EMAIL_DISPATCH_ENABLED` switch controls both Claim and cooperation email dispatch; keep it enabled for delivery in deployment. It does not change synchronous registration/OTP delivery.


### Collaboration execution and Outcome (V29)

A confirmed Deal still creates one PLANNED Collaboration and one fee, including multi-channel Deals. New proposal deliverables now require `dueAt` (UTC instant), `acceptanceChecklist` (1-50 nonblank strings), and integer `maxRevisionRounds` (0-10). Quantity (1-100) expands into individual execution units, with a maximum of 1,000 units per agreement. Confirmation snapshots these terms; old proposals missing these terms must be replaced explicitly before confirmation. No historical terms are invented.

Execution lifecycle: `PLANNED -> IN_PROGRESS -> AWAITING_REVIEW -> COMPLETED`. Start remains restricted to ACTIVE Campaigns. Recording Outcome no longer completes work. CRM stays CONFIRMED; payment, commitment and agreedFee are independent. Campaign completion remains a separate Brand action and requires at least one completed Collaboration, no unfinished Collaborations and no open negotiating/contacted Deals.

API prefix `/api/v1`. Authenticated endpoints under `/collaborations/{id}`:

- `GET /deliverables`: current executionRevision, per-unit revision/submissionRevision, immutable submission/review/publication history, outcomeRevisions, acceptances and final review. Responses use camelCase and preserve explicit nulls for absent data. This is the authoritative execution view; the existing collaboration detail retains its legacy outcome projection, populated on final review.
- `POST /deliverables/{deliverableId}/submissions`: expectedRevision is the **unit** revision; HTTPS url and source CREATOR_PORTAL or BRAND_EXTERNAL. Brand external submissions require sourceDescription. Pending submissions must be reviewed before replacement. New submissions after approval require approval again.
- `POST /deliverables/{deliverableId}/review`: unit expectedRevision, submissionRevision, decision APPROVE/REQUEST_CHANGES, completedChecklist, reason. Approval requires the full negotiated checklist. Changes consume one negotiated round; exceeding the limit returns 409.
- `POST /deliverables/{deliverableId}/publication`: unit expectedRevision, submissionRevision, HTTPS url, optional postId, acknowledgeException and exceptionReason. Early publication is retained as an exception; only Brand can acknowledge it. Approval and acknowledgement are required before acceptance. This does not publish to social media or verify that published media equals the approved draft. URLs are not fetched by the server.
- `POST /outcome`: collaboration expectedRevision, reason and posts. Each post contains deliverableId, submissionRevision, periodStart/periodEnd and nullable nonnegative integer views/clicks/likes/comments/shares, optional evidenceUrl. One measurement per current publication/unit; duplicate posts are rejected, not double-counted. New measurements append immutable revisions. Each Outcome POST is a complete measurement snapshot: Brand-omitted finance remains unknown rather than copying agreedFee; Creator metrics retain the last explicitly recorded Brand finance until Brand accepts the new revision. Creator may report metrics but may not provide finance fields.
- Brand finance fields: costs[{description,amount}], currency=VND, optional gmv/revenue, periodStart/periodEnd, optional financialEvidence HTTPS URL, financialConfirmed boolean. actualCost is the sum of explicit costs, never an agreedFee default. Financial data and evidence are self-reported; URLs are references, not proof that the server checked their contents.
- `POST /outcome/accept`: collaboration expectedRevision and current outcomeRevision. Requires all units approved, current publications measured, early-publication exceptions acknowledged, and positive confirmed VND actualCost. Transitions only to AWAITING_REVIEW.
- `POST /outcome/reopen`: collaboration expectedRevision and reason; only AWAITING_REVIEW can reopen to IN_PROGRESS. The previous acceptance remains in history and is marked reopened. COMPLETED cannot reopen.
- `POST /review`: collaboration expectedRevision, accepted outcomeRevision, integer overallRating/brandFitRating (1-5), wouldCollaborateAgain boolean, note and reviewTags. Final review completes execution atomically with historical evidence and audit. Final reviews are immutable.

All POST execution operations require `Idempotency-Key` (1-100 safe characters). Same actor/key/scope/body returns the stored response; changed body or stale revision returns 409. Unit mutations also increment the collaboration executionRevision. Keys are scoped to operations, not shared with authentication tokens. Brand writes use existing Collaboration/Outcome/Review permissions; Creator execution uses effective RESPOND_OWN_COOPERATION and current ownership of **every** Deal channel. Losing ownership removes access. No new public/magic-link access to execution data is introduced.

Financial policy `outcome-finance-v1`: ROAS=GMV/actualCost, ROI=(revenue-actualCost)/actualCost, CPM=actualCost/views*1000, CPE=actualCost/(likes+comments+shares). ROI is not full accounting profit. Calculations use BigDecimal; missing inputs/zero denominator/inconsistent measurement periods return null with an unavailable reason. No absent metric is filled with zero. Raw revision calculations remain authoritative; a ratio outside the legacy numeric ROI column range is not truncated into that column.

Post identity is derived from recognized platform URL IDs when possible, otherwise normalized HTTPS URL (tracking parameters removed; content-identifying query retained). Optional postId must agree with the recognized URL identity. No network redirect expansion is performed: different shortlinks cannot be proven identical in this version. Claims of provider verification are never inferred from a user-supplied URL or ID.

V29 preserves old Collaboration/Outcome/Review rows and labels their provenance LEGACY_UNVERIFIED. It creates no past approvals, deadlines, consent or acceptance. An unfinished legacy Collaboration needs `POST /execution-plan` with collaboration expectedRevision, sourceDescription and fully specified deliverables before using the new workflow. This is explicit Brand recording of prior terms, not a new claim of Creator acceptance. A historical COMPLETED Collaboration with missing review uses `POST /review/legacy` (expectedRevision, ratings, wouldCollaborateAgain, note, reviewTags); this creates no content acceptance. The old generic outcome/review JSON contract is replaced for new HTTP writes; frontend must use the revision-aware endpoints above.

M1 evidence records source review/revision once, actual persisted ratings (including negative reviews), acceptance provenance and SELF_REPORTED metrics. A Brand review never upgrades metrics to VERIFIED_CRAWL. One multi-channel agreement contributes one review, not one per channel. The legacy pattern counter is no longer incremented; learned M4 is rebuilt from authoritative reviews. M1 scoring is deliberately unavailable with `M1_POLICY_NOT_IMPLEMENTED` until a Bayesian/KPI policy is specified. New Recommendation runs identify this change with scoring policy `four-memory-v2-m1-unavailable`; historical run policies/results are preserved. Available components retain normalized weights, and historical RecommendationRun results are untouched.

No crawler-post adapter, social publishing, barter, frontend or shared-environment rollout is included. V29 has only been exercised on disposable PostgreSQL containers; deployment requires the API/FE contract changes together. Do not apply this migration to a shared database without the normal deployment review.

V29 verification: full regression 1,117/1,120 passed (only the three pre-existing Payment failures, zero errors/skipped); final affected-suite verification 69/69 passed, including UTC/UTC+7 period normalization. Detailed cases and run boundaries are recorded in `docs/backend-test-cases.md`. Real environment files, Bruno, shared DB, the serving BE and commit/push remain unchanged.


## Campaign History and Duplicate Brief (Luồng 10, V30)

History endpoints require an ACTIVE, email-verified BRAND owning the Campaign and **both** `VIEW_OWN_CAMPAIGN` and `VIEW_OWN_HISTORY_AND_INSIGHTS`. Admin/Creator cannot use these new routes. Missing/foreign Campaigns and foreign child resources return 404. Existing routes retain their own authorization. Private agreement terms, financial references and internal reviews are intentionally covered by the History permission; read access does not require write permissions.

API prefix `/api/v1`:

- `GET /campaign-history`: `status`, `platform`, inclusive `createdFrom`/`createdTo` dates against stored `createdAt`, literal case-insensitive name `q` (max 200 characters); page=0, size=20, size 1–100. Order: createdAt DESC, id DESC. Invalid/reversed bounds return 400.
- `GET /campaigns/{id}/history`: separate currentCampaign, latest stored Context, deal counts, completed-only report and optional duplicate origin.
- `GET /campaigns/{id}/history/contexts`: paginated immutable brief versions and Brand Context references. Absent snapshot attributes remain null with missingFields.
- `GET /campaigns/{id}/history/collaborations`: paginated, optional collaboration `status`.
- `GET /campaigns/{id}/history/collaborations/{collaborationId}`: exact confirmed proposal from confirmation history, member channels, execution submissions/decisions/publications, Outcome revisions, acceptances and final Review. Current channel names/profile are labelled CURRENT_PROFILE; they are not historical identity snapshots.

GETs use read-only repeatable-read transactions. They never prepare M3, crawl, dispatch embedding jobs, produce learning evidence or recalculate stored RecommendationRun results. Execution and negotiation queries are shared with the existing execution/CRM APIs, with independent History read authorization. No proof URLs are fetched. Legacy missing snapshots/acceptance are labelled LEGACY_UNVERIFIED, never silently upgraded to VERIFIED_CRAWL or reconstructed from current data.

Report policy `campaign-history-report-v1` references `outcome-finance-v1`. Only COMPLETED Collaborations with final Review referencing the exact SELF_REPORTED Outcome revision and an active Brand acceptance enter the main report. Unfinished/cancelled/legacy missing provenance remain visible separately. Each multi-channel Collaboration contributes one commitment and one explicit actualCost, never one per channel. Commitment is shown separately; actualCost is never inferred from agreedFee or legacy actual_fee.

Metrics include nullable value, availability, unavailableReasons, unit and coverage (required completed sources, eligible reviewed/accepted sources and distinct post keys). Unavailable inputs do not become zero or partial campaign totals. Only valid VND amounts are summed. Public post keys deduplicate identical measurements; conflicting values or incompatible measurement periods make affected metrics unavailable. Views are counts, not unique reach. CPM/CPE use totals, not average ratios. One qualifying Collaboration may report its own GMV/revenue/ROAS/ROI; multiple completed Collaborations return `ATTRIBUTION_NOT_ESTABLISHED` for revenue-dependent totals, preserving their individual stored outcomes. This is a conservative History report, not the full Analytics dashboard or independent verification of claims.

### Duplicate integration contract

Preview uses the two History read permissions; confirmation additionally requires CREATE_CAMPAIGN:

1. `GET /campaigns/{id}/duplicate-brief/preview?contextId=<optional UUID>` reads the selected Context, defaulting to the latest stored version. Without a Context it explicitly uses CURRENT_DRAFT or CURRENT_RECORD_FALLBACK. It returns source revision/kind/context, a whitelisted draft, resetFields, reviewFields and warnings. Preview does not write or consume quota.
2. Review/edit the returned draft. Dates are reset to null and may remain absent in DRAFT; Brand must supply valid dates before READY. Missing snapshot values are not filled from current records. Legacy budgetMin/budgetMax are never converted into totalBudget or per-Creator fee.
3. `POST /campaigns/{id}/duplicate-brief` with `Idempotency-Key` (1–100 letters/digits/`.`/`_`/`:`/`-`) and body `{expectedSourceRevision,sourceKind,sourceContextId,draft}`. expectedSourceRevision is the nonnegative integer returned by preview (initial Campaign revision is 0). draft uses CampaignRequest. Return 201 `{campaign,source}`. Source changes or same key with different content return 409. Missing/invalid request fields return 400.

The submitted draft is the full editable input, not a JSON merge with source. New Campaign is DRAFT and owns no Context/M3 until READY. Only generic brief/preferences/budget/deliverables are suggested. Channels, Shortlist, CRM, Deals, Collaborations, agreed fees, consent, Outcome, Review, evidence, exclusions, feedback, embedding/jobs and RecommendationRuns are never copied. READY creates new Context/M3 with the Brand Context applicable then.

V30 adds only campaign_duplicate_request receipts, with source/actor/target FKs and actor-key/target uniqueness. Existing Campaigns receive no fabricated origin. Source User/Campaign locks, atomic quota reservation shared with normal create, target, receipt and audit commit together. Concurrent retry returns the stored response and does not charge quota twice, even when quota is now full. Audit/receipt failure rolls back target and quota. Payment activation and settlement policies are unchanged.


V30 validation (2026-11-09): related suite **105/105 passed**. Whole backend regression **1,155 run; 1,152 passed; 3 failures; 0 errors; 0 skipped**, with exactly the existing Payment failures documented in the test cases. After the final shared-query refactor, calculator/quota/Deal/execution companion suites passed 103 cases; the final History PostgreSQL invocation passed **16/16**, including the real two-channel, one-fee workflow. These final affected checks are separate from the whole-backend run. PostgreSQL was disposable; provider responses were mocked where needed, and no shared database migration or serving-BE restart was performed.


## Creator Portal profile, sharing and verification (V31)

V31 adds a private `creator_portal_profile` unique per CreatorMaster and ordered `creator_portfolio_item` rows. Existing Master, ownership, contacts, consent and verification are not backfilled or rewritten. No Rate Card or bank data is introduced. Profile data is explicitly CREATOR_DECLARED and never overwrites platform location, niche, crawler snapshots, ranking inputs or Collaboration terms.

API prefix `/api/v1`:

- `GET /creators/portal/profile`: CREATOR ACTIVE, verified email and VIEW_OWN_PROFILE. Returns `{masterId,profileExists,profileRevision,source,profile}` plus ETag `"creator-profile:<userId>:r<revision>"`. Missing profile/Master produces an empty virtual profile at revision 0, without writes.
- `PATCH /creators/portal/profile`: also requires MANAGE_OWN_CREATOR_PROFILE. Supply the exact If-Match from GET; missing=428, malformed=400, stale=409 CREATOR_PROFILE_REVISION_CONFLICT. Initial meaningful PATCH creates the profile/Master under User → Master → Profile locks. Empty/no-op updates do not create rows, increment revision or audit a change.
- `GET /creators/{id}/contacts`: verified ACTIVE BRAND with VIEW_CREATOR_CONTACT. Returns only permitted values, availability, source and verified=false for self-declared/public-crawl contacts. Hidden and absent contacts both appear as NOT_SHARED/null; no account login email is exposed.
- `GET /creators/portal/accounts/connection-options`: verified ACTIVE CREATOR with CLAIM_CREATOR_PROFILE. Reports locally configured Facebook Page, Instagram via Page, direct Instagram and TikTok Sandbox flows. No provider request is made, no secret/configuration URL is returned. YouTube remains outside MVP. available means local configuration passes validation, not that provider consent, asset access or App Review has succeeded.

Profile PATCH is a strict partial object. Omitted properties survive; optional null clears and [] clears lists. Unknown fields are rejected, including bank/ownership/verification data. Example:

```json
{
  "introduction": "Creator introduction",
  "serviceAreas": ["Ho Chi Minh City"],
  "acceptedNiches": ["Beauty"],
  "portfolio": [{"title": "Example work", "url": "https://example.com/work", "description": "Self-declared portfolio"}],
  "availability": {"status": "OPEN", "availableFrom": "2026-11-10", "note": "Contact to discuss scope"},
  "sharing": {"introduction": true, "serviceAreas": true, "acceptedNiches": true, "portfolio": true, "availability": false},
  "contacts": {"bookingEmail": {"value": "booking@example.com", "visibility": "BRANDS"}}
}
```

introduction max 4,000 characters; up to 20 nonblank serviceAreas (150 characters) and acceptedNiches (100); up to 20 portfolio items (title 200, HTTPS URL 2,000, description 1,000). Lists are trimmed, deduplicated and sorted. Portfolio is a full replacement, preserves order and uses server-generated IDs; existing IDs must belong to the profile. Resend returned IDs for edits. HTTPS URLs cannot contain credentials; the backend never fetches or previews them. Availability is nullable OPEN/LIMITED/CLOSED, optional date and note up to 1,000 characters, not checked calendar/capacity and never a booking constraint.

Basic sharing flags default true; availability sharing defaults false. This is visibility inside existing authenticated Creator views, not a new anonymous public API. New public fields require valid current ownership evidence; private owner GET remains available without a linked channel. Creator detail/Compare include the allowed portfolio; Discovery/Shortlist use summaries. Responses containing the new projections use private/no-store semantics. Crawler data continues to update independently and does not erase Creator declarations.

bookingEmail, zalo and managerContact each have value/visibility, default PRIVATE. A null contact block clears the value and resets PRIVATE; nested omitted values survive. bookingEmail is syntax-validated, not OTP-verified or automatically used as an outbound recipient. Visibility BRANDS permits authenticated, authorized Brand readers; CONFIRMED_PARTNERS additionally requires a noncancelled CONFIRMED Deal for the same Master, with every member channel still belonging to that Master. Completed Collaboration can remain a confirmed partnership; cancelled or ownership-mismatched Deals do not qualify. Admin/Data Manager do not get private-contact access through these new routes.

For owned channels, private/missing Portal contacts do not fall back to crawler contacts. Shortlist Readiness no longer returns owner login email in contactTarget: it can report IN_APP_PORTAL readiness without revealing an address. For unclaimed channels, permitted public-crawl booking contact retains PUBLIC_CRAWL provenance. Backend cooperation notification delivery continues to the owner's verified account email, independently of disclosure settings; it is not redirected to a self-declared booking address. New profile contacts are not copied into Deal/history/receipt/SSE payloads. Audit stores changed fields and visibility choices, not raw contact values.

### Evidence-based badge compatibility

The shared HTTP read projection enriches existing Creator detail/search, campaign Discovery, Shortlist/Compare and Portal account/Reach responses in batches. It does not change search scores, recommendation formulas, ownership or stored evidence. Typed verification metadata reports status, method, verifiedAt, evidenceSource and environment; provider tokens/granted payloads are never returned.

- Verified requires current AUTHORIZED ownership, an ACTIVE verified Creator account/Master, and matching current-owner evidence: META_OAUTH, INSTAGRAM_OAUTH, TIKTOK_OAUTH_SANDBOX or an actual APPROVED manual Claim.
- AUTHORIZED without qualifying evidence becomes LEGACY_UNVERIFIED in the read model, with verifiedByInfluencerMatch=false. Owner/Master/sourceType remain intact. Reconnect OAuth or complete an allowed manual Claim to obtain qualifying evidence; old rows are not fabricated into official verification.
- Simulated evidence is SIMULATED, not a genuine badge. TikTok reports SANDBOX. Meta reports OFFICIAL_API, which does not assert production App Review or distinguish Meta app Development/Live mode. Manual approval reports MANUAL_REVIEW/CREATOR_CLAIM, not OAuth.
- Revocation and wrong-owner evidence cannot support a badge. Token expiry is separate from ownership evidence; Reach tokenStatus is deprecated and reports NOT_STORED for proofs without stored tokens rather than inferring ACTIVE from AUTHORIZED.

The badge means InfluencerMatch verified management authority at the recorded time. It is not the platform's official badge, a price guarantee, endorsement of profile claims or a performance score. Manual Claim/review/revoke, the three separate invitation domains and all existing OAuth ownership writes remain in place. No frontend, bank storage, Rate Card, calendar, exclusivity or new platform integration is included.

## Data Manager Master Catalog and Claim review (V32)

Master existence and channel verification are separate. Invitation registration may create a Master before any channel is linked; crawler ingestion never creates a Master by matching names. This release adds read-only Catalog/history APIs and strengthens the existing manual fallback. It does not add transfer, merge or dispute processing.

All paths below have prefix `/api/v1`, return `ApiResponse`, and require an ACTIVE ADMIN or DATA_MANAGER and a valid login session. `VIEW_CREATOR_MASTER_CATALOG` and `VIEW_CREATOR_IDENTITY_HISTORY` are separate dynamic permissions, enabled for those two roles by default. They do not grant `VIEW_AUDIT_LOG` or access to private contacts.

- `GET /data/creator-masters`: `q`, `status=ACTIVE|SUSPENDED`, `platform=FACEBOOK|INSTAGRAM|TIKTOK|YOUTUBE`, `verificationStatus=VERIFIED|UNVERIFIED|LEGACY_UNVERIFIED|SIMULATED`, `page`, `size`. Search matches canonical name literally, case-insensitively, or exact Master UUID. Platform/verification filters must match the same member channel; Masters without channels remain visible without channel filters.
- `GET /data/creator-masters/{id}`: current identity metadata, channel count and counts by verification state. No login email, Master notes or private profile contacts.
- `GET /data/creator-masters/{id}/accounts`: paged current member channels and verification metadata shared with Portal.
- `GET /data/creator-accounts/unclaimed`: channels with no Master; optional `q`, `platform`, `sourceType=PUBLIC|AUTHORIZED`, `isStableId`, `page`, `size`. Search uses username/display name/external ID; `%` and `_` are literal. A channel without Master is not verified, even if a legacy source label says AUTHORIZED.
- `GET /data/creator-identity-events`: `masterId`, `creatorId`, `claimId`, `actorId`, `eventType`, `from`, `to`, `page`, `size`. Dates use ISO local date-time against persisted values, with inclusive boundaries; no historical timezone is inferred or rewritten. Reverse ranges are rejected. Identity audit and Claim/ownership business audit remain distinct sources; source ID/source type are returned without raw audit JSON, free text, proof URLs or tokens. Missing historical Master references remain null, not filled from current ownership. A Master filter cannot match a business-audit event whose historical Master was never recorded.

Pagination defaults to page 0/size 20, size 1–100. Invalid page, size, enums or search over 200 characters return 400; missing Master returns 404. Catalog ordering is createdAt DESC/id DESC; event ordering is occurredAt DESC/source DESC/sourceId DESC. Responses are private/no-store. Reads do not create profiles, audit mutations, jobs or evidence.

Manual Claim endpoints keep their existing request/response shape and `REVIEW_CREATOR_CLAIM` permission. Approval rechecks actor/claimant, ownership and invitation under database locks; it cannot transfer an owned channel. A new Claim submitted under a live invitation stores its reference. Approval checks that invitation is still PENDING, addressed to the claimant and strictly unexpired; equality with now is expired. Wrong email returns 403, expired/processed invitation 409. Historical claims retain null invitation references; current live invitation restrictions still apply. Rejecting an expired Claim remains possible. Valid approval consumes the applicable invitations, updates current Shortlist/Relationship Master references and audits atomically.

Revoking an APPROVED manual Claim revokes that evidence, with reason/reviewer/time retained. An independent valid OAuth proof for the current owner keeps ownership/badge. Without valid remaining proof, owner/authorization/current references are cleared, while Deal/Collaboration members, bindings and historical snapshots remain intact. Each OAuth method uses its latest verification state; an old ACTIVE row cannot resurrect a later-revoked proof of the same method. Mock, wrong-owner and unsupported legacy methods cannot preserve ownership as valid proof. Token expiry is independent of verification. Audit/synchronization failure rolls back the business change; ownership denial still records its dedicated conflict audit.

V32 adds the nullable Claim invitation FK/index, Catalog/event indexes and read views for effective evidence and Catalog state. It performs no ownership/profile/evidence backfill. The old transfer URL remains unmapped (404), and the legacy Java mutation path is hard-denied. Existing conflict history remains readable. Apply migration only through the normal deployment process; this implementation does not apply it to shared databases.

## System Admin Console — AD-01 / AD-05

Account and permission-matrix endpoints require an ACTIVE ADMIN, a valid persisted login session and the endpoint's capability at both controller/service boundaries. Administration responses use `Cache-Control: private, no-store`. Account DTOs remain unchanged and never expose password hashes, refresh tokens, Creator private contacts or banking data.

### Account management contract

`GET /api/v1/admin/users` accepts optional `role=ADMIN|DATA_MANAGER|BRAND|CREATOR`, `status=ACTIVE|LOCKED|DISABLED`, `q`, `page`, `size`. Search trims q and matches literal email/full-name substrings case-insensitively, or an exact canonical UUID (either case). `%`, `_` and `!` are literal characters. q is at most 200 characters. Defaults are page 0/size 20, size 1–100; invalid enum/pagination/search length returns 400 instead of silently clamping. Ordering is `createdAt DESC, id DESC`.

Keep `GET /admin/users/{id}`, `PATCH /admin/users/{id}/status` and `PATCH /admin/users/{id}/role`. Status/role writes require `reason`: trimmed, nonblank, 1–500 characters, including no-op requests. Example status body: `{"status":"LOCKED","reason":"Reviewed policy violation"}`. A missing target returns 404. These writes do not require If-Match or a new user revision: valid requests serialize against the latest state, so this contract does not reject a stale administrative screen using optimistic concurrency.

Writes lock the RBAC guard and then actor/target Users in UUID order, refresh state and recheck role/status/authVersion/session before mutation. An actor who lost authorization while waiting receives 403; an expired/revoked session receives 401. Losing authorization cannot change the target. The last ACTIVE Admin cannot be locked, disabled or demoted. Self-lock/self-disable/self-demotion is allowed while another ACTIVE Admin remains; the current request can return its result, then the actor must authenticate again with an allowed account. No new password/OTP reauthentication step is added.

A real status or role change increments authVersion, revokes all target auth_session/refresh_token rows and audits actor/target, old/new state and reason atomically. Explicit actor ID preserves audit attribution for self-actions. Audit or revocation failure rolls back the entire operation. No-op does not revoke sessions, increment version or invent an audit event. Unlock never restores revoked sessions; previous access/refresh credentials remain invalid. Effects apply from the next request after commit; in-flight responses/SSE are not interrupted. Business records and ownership are retained, and existing Brand-profile/Creator-ownership/pending-Claim role constraints remain enforced.

### Permission matrix contract

Keep `GET /api/v1/admin/role-permissions` and `PATCH /api/v1/admin/role-permissions/{role}`. ADMIN plus `ASSIGN_USER_ROLE` is required; ADMIN capabilities remain immutable. Only implemented capabilities already in the target BRAND/CREATOR/DATA_MANAGER default allowlist can be toggled. There are no arbitrary cross-role grants or custom roles.

PermissionCatalog now explicitly registers the existing History, run/read Recommendation, Creator profile/contact, cooperation response, notification and Master Catalog/identity-history endpoints. This makes their matrix entries implemented/editable for eligible roles. Provider/scoring configuration, collection/monitoring and other absent functions remain unimplemented/noneditable; enum declarations or a nearby legacy read endpoint do not claim the corresponding function exists.

Matrix writes lock RBAC guard → Admin User → role config and revalidate authorization/session before writing. Existing version conflicts return 409. Override changes, config version and audit commit together; a stale version, invalid capability or audit error cannot partially apply a multi-capability update. No-op preserves version/audit. Permission changes apply on the next request using the same JWT without raising every user's authVersion or revoking the role's sessions. EffectivePermissionService keeps only a per-request snapshot, never a cache between requests. Scoring activation and algorithm changes remain outside this release.

Storage remains `user`, `auth_session`, `refresh_token`, `role_permission_config`, `role_permission_override`, `audit_log`. No new migration or edits to V1–V32 are needed. Frontend clients must supply status-change reasons and handle strict pagination validation; Bruno and real environment files are not updated by this implementation.

## Subsystem integration into develop

Includes completed Auth, Brand/onboarding, Campaign/M3, Shortlist, Deal/Outreach, Collaboration/Outcome, History, Creator Portal, Catalog/Claim and Admin/RBAC through V32. The new v3 Bayesian M1/M4 policy, KPI targets and V33 are excluded. Develop TOPSIS/CRITIC and its scoring/presentation rules remain active; older implementation notes about scoring are historical, not a change to that algorithm.

Migration chronology on develop: existing V1–V19 are preserved unchanged. New Meta OAuth sessions and Instagram login are V20/V21, followed by V22–V32. This numbering follows develop, and is not interchangeable with a database migrated using khai’s different V17–V21 history; no shared database is migrated by this integration. V33 is excluded.

Validation for develop subsystem integration (2026-11-10): full regression executed 1,361 tests; after updating migration-count and legacy unit fixtures to the new subsystem contracts, the affected Meta/Recommendation/engine suites passed 162/162 including the new Run DTO privacy test. Consolidated Surefire results: 1,362 tests, 1,359 passed, 3 failures, 0 errors, 0 skipped. Remaining failures are the known Payment tests: PaymentSettlementServiceTest.activeSubscriptionPreventsActivationWithoutMutatingPendingPayment, ComparePaymentPostgresIntegrationTest.parallelPaymentsForSameBrandCannotActivateTwoSubscriptions, and RbacPostgresIntegrationTest.paidSubscriptionRequiresCheckoutAndGatewayVerification. Payment behavior/assertions were not changed to hide these failures. PostgreSQL tests used disposable Testcontainers; no shared database or live backend was restarted.


### Authenticated password change

`POST /api/v1/auth/change-password` requires a live Bearer JWT and the CSRF cookie/header from `GET /api/v1/auth/csrf`.
Body: `{"currentPassword":"...","newPassword":"..."}`. No email or user ID is accepted: only the authenticated account is changed.
The new password must contain 8–72 characters, fit within 72 UTF-8 bytes for BCrypt, and differ from the old password.
On success: `204 No Content`, BCrypt password update, authVersion increment, all sessions/refresh tokens revoked, and refresh cookie cleared atomically. Log in again on every device.
Incorrect current password or invalid new password returns 400; unauthenticated/revoked sessions return 401; missing/invalid CSRF returns 403. Passwordless Google accounts must first use the existing password-setup flow.
This source change does not deploy the endpoint or change any account password by itself.

### Managed niche migration after merging develop

The managed niche catalogue migration is V35__managed_niche_catalog.sql. Develop owns V33 (collaboration confirmation source) and V34 (creator fee/discovery keywords); their SQL is unchanged. Fresh databases apply each version once. If a database has already applied this branch's former V33__managed_niche_catalog.sql, reconcile its Flyway history with the deployment owner before upgrading; this source rename does not rewrite any existing database history.
V36 adds the three nullable follower-growth columns already mapped by PublicCreatorMetric. Historical growth remains NULL; existing metric values are preserved.
