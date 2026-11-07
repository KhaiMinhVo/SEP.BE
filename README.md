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
Các API sử dụng JWT stateless; Google Login dành riêng cho Brand có thể bật qua GOOGLE_LOGIN_ENABLED; Google Login không cấp ownership kênh.

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

Backend supports Google Login only for BRAND. Creator invitation/password/OTP, existing platform verification and password setup remain unchanged. New Google users are verified BRAND accounts without passwords; they may use the existing password-setup OTP endpoints to set a first password. Brand Profile is completed through existing onboarding APIs. No automatic profile with invented business fields is created.

Set `GOOGLE_LOGIN_ENABLED=true` and `GOOGLE_LOGIN_CLIENT_ID=<Web OAuth client ID>` on deployment. Default is disabled (503). This GIS ID-token flow does not require a client secret or a backend Google redirect URI. In Google Cloud, register the frontend origin under **Authorized JavaScript origins**. Use the official GIS script and popup callback, requesting no YouTube/other API scopes. Backend verifies Google's RS256 signature with its fixed JWKS endpoint, issuer, audience/authorized party, expiry, subject and verified email. Key retrieval uses 5-second timeouts; no Google token is persisted or logged.

Frontend integration (frontend code is not implemented here):
1. `POST /api/v1/auth/google/start`, with browser credentials enabled. Response: `data.sessionId`, `data.clientId`, `data.nonce`, `data.expiresAt`. Backend sets a HttpOnly SameSite=Lax cookie, Secure over HTTPS. Session lasts 10 minutes; nonce/browser values are stored only as hashes. The JSON nonce is intended for GIS, not the cookie value.
2. Call `google.accounts.id.initialize({client_id: data.clientId, nonce: data.nonce, callback: ...})`, then render the Google button. Use a fresh start for each login attempt; do not cache credentials.
3. In the GIS callback, send JSON `{ "sessionId": "<start ID>", "credential": "<GIS response.credential>" }` to `POST /api/v1/auth/google/login`, with browser credentials enabled. Do not send email, role or profile fields. For local GIS testing, use FE `http://localhost:5173` and BE `http://localhost:8080`, with the FE origin registered in Google Cloud; no ngrok redirect is required. Use the same browser and backend hostname for start/login; production FE/BE should be same-site HTTPS (e.g. influencermatch.site/api.influencermatch.site). Spring Security uses the existing explicit CORS origin list with credentials enabled; set CORS_ALLOWED_ORIGINS to the actual frontend origin. No wildcard CORS or cross-site cookies are introduced.
4. Success returns the existing `LoginResponse` (access/refresh tokens and effective permissions). Store/use it exactly as password login, then check Brand onboarding. Google token must not be used as the application's API access token.

Known Google identity is looked up by subject, not email. Existing password accounts (including verified accounts) are never linked automatically: 409 `GOOGLE_ACCOUNT_LINK_REQUIRED`; sign in with the existing password. An account waiting for OTP remains blocked with 403 `EMAIL_NOT_VERIFIED`. There is no new manual-link endpoint in this change. Known non-Brand Google users receive 403; locked/disabled accounts receive 401. Invalid credential/nonce/browser/expired session receives 401; reuse after successful login receives 409 `GOOGLE_LOGIN_REPLAY`.

Migration V24 adds only `google_login_session`; existing identities/data and V1–V23 are retained. Apply migrations only in an authorized deployment. Automated tests use generated RSA signatures and mocked provider verification with disposable PostgreSQL; a real Google login requires frontend GIS setup and manual consent and has not been performed by these tests.

Official integration reference: https://developers.google.com/identity/gsi/web/guides/verify-google-id-token
