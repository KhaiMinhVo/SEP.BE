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

POST /campaigns/{id}/ai-recommendations?candidateSource=SHORTLIST|DISCOVERY defaults to SHORTLIST. SHORTLIST never silently falls back to Discovery. Empty eligible source returns 409 RECOMMENDATION_NO_CANDIDATES. Campaign not ready or embedding not READY returns 409 (CAMPAIGN_EMBEDDING_NOT_READY includes embeddingStatus). These failures do not reserve recommendation quota or create a Run. Recommendation reads persisted M3; no synchronous embedding request or zero-vector fallback remains.

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
