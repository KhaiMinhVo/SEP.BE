# Backend Test Cases — Khải

## Current verification — Claim Email Outbox + Async (2026-10-23)

- New outbox suites: **23/23 passed** across five suites. Migration/schema/SMTP follow-up: **58/58 passed** across four distinct suites.
- Final full regression: **589/589 passed, 69 suites; 0 failures, 0 errors, 0 skipped**, Java 21, Maven `test` with `-Dlogging.level.org.hibernate.SQL=OFF` to suppress SQL debug output only.
- Added 23 invocations over the 566-test baseline: 14 PostgreSQL outbox cases, one PostgreSQL after-commit case, one PostgreSQL missing-key case, four cipher unit cases and three async/logging cases.
- SMTP/provider remain mocked; PostgreSQL/pgvector Testcontainers exercise actual transaction, lease, unique constraint, migration and lock behavior. No real Gmail delivery was attempted. Production code uses existing JavaMailSender.
- Initial targeted checks exposed two test-fixture problems (mock EntityManagerFactory missing in the standalone async context and an AUTHORIZED fixture without its required Master/time); fixtures were corrected. First full run identified 13 stale migration-count assertions: V14→V15 tests now pin V15, fresh-schema counts/version include V16. All preservation assertions remain active, and no tests were disabled or skipped.
- V16 adds only claim email outbox/retry tables. No `.env`/Bruno edits, shared database access/migration, BE restart, commit or push. Registration invitation and OTP SMTP behavior is unchanged.
- API/SMTP/frontend instructions: [claim-email-outbox.md](claim-email-outbox.md). Executable mappings: MAIL-001–MAIL-013 below. Earlier results are historical.

## Previous verification — Creator Claim Concurrency (2026-10-23)

- Targeted regression: **69/69 passed**, five suites, zero failures/errors/skips.
- Full regression: **566/566 passed, 64 suites; 0 failures, 0 errors, 0 skipped**, Java 21, Maven `test`.
- Added 11 test invocations: four PostgreSQL cases (two-channel concurrent claims, two expiry boundaries, used invitation) and seven unit cases for state refreshed under locks. Existing race/replay and audit rollback assertions were strengthened, not removed.
- The invitation/conflict PostgreSQL suite now has **31 passing invocations**. Clock, SMTP and provider are mocked; transaction, row locks and rollback run on disposable PostgreSQL/pgvector Testcontainers. Mockito unit cases verify ordering and guards only.
- Initial sandbox execution could not access the Docker named pipe; rerun with local Docker access completed successfully. Final targeted/full results above contain no environment errors or skipped tests.
- No API/schema changes, `.env`/Bruno edits, shared database access, real mail, BE restart, commit or push. Executable mappings: CONC-001–CONC-006 below.

## Previous verification — Claim Invitation & Conflict MVP (2026-10-23)

- Final full regression: **555/555 passed, 64 suites; 0 failures, 0 errors, 0 skipped**, Java 21 and `mvn -q test`.
- Added **27 PostgreSQL invitation/conflict invocations** and one SMTP message unit case. Provider, email and Clock are mocked; locks and rollback use real disposable pgvector/PostgreSQL containers.
- Checks include global pending blocks, ownership/campaign/RBAC checks, concurrent invitations/callbacks, replay/expiry, durable 409 audits, SMTP/audit rollback, manual Claim matching email and retired transfer API 404.
- Earlier runs identified expired-status/fixture expectations and an incomplete mocked Clock zone; fixtures were aligned with the read-only verify contract and configured JPA UTC behavior. No assertions were disabled or tests skipped.
- V1–V15 unchanged, no new schema migration. No real mail, shared database access/migration, `.env`/Bruno edits, BE restart, commit or push. Earlier results below are historical.
- API/frontend instructions: [claim-invitations-mvp.md](claim-invitations-mvp.md). Executable mappings: INV-001–INV-016 below.


## Previous verification — schema cleanup V15 (2026-10-23)

- Targeted regression: **100/100 tests passed**.
- Final full regression: **527/527 passed, 63 suites, 0 failures, 0 errors, 0 skipped**, Java 21 and `mvn -q test`.
- Added 12 PostgreSQL integration invocations for V15: nine backfill parameter sets, retained-data upgrade, fresh schema and ingestion/filter/Discovery/profile/Compare behavior.
- New migration V15 drops the retired tables/columns; prior V1–V14 migration files remain unchanged. Historical V12/V14 tests are pinned to their historical schema versions.
- Only disposable PostgreSQL/pgvector containers were migrated. No real email, shared database migration, backend restart, .env/Bruno changes, commit or push.
- Data impact and deployment notes: [schema-cleanup-v15.md](schema-cleanup-v15.md). SCHEMA-001–SCHEMA-005 map the current checks below; earlier test counts are historical.


## Previous verification — invitation registration and OTP (2026-10-23)

- Final full regression: **515 tests / 62 suites; 515 passed, 0 failures, 0 errors, 0 skipped** (`mvn -q test`, Java 21).
- PostgreSQL/pgvector Testcontainers covered V13/V14, rollback, persisted wrong attempts, invitation concurrency, verification concurrency and legacy password setup. SMTP/clock were mocked; no real mail or shared database was used.
- Google Login-only suites were retired; their replacement checks assert removed endpoints and legacy password setup. Existing session, permission, Claim/ownership, Compare, Billing and Collaboration assertions remain, with fixtures adapted to Vy's persisted CreatorMaster and migration numbering.
- Regression caught and fixed verification being overwritten by concurrent Admin role changes (`User` dynamic updates), transient Master assignment in manual Claim approval, and catalog entries incorrectly marked editable when unimplemented.
- V1–V12 were not modified. New migration files were tested only in containers. No `.env`/Bruno edits, shared database migrations, BE restart, commit or push were performed.
- Current flow/configuration: [registration-and-email-otp.md](registration-and-email-otp.md). REG-001–REG-025 below map the added/updated behavior to executable tests. Older results below are historical.


## Kết quả thực thi ngày 22/10/2026 — gỡ Creator Rate Card

- Clean full regression: **362 test / 47 suite; 362 đạt, 0 thất bại, 0 lỗi thực thi, 0 bỏ qua**.
- Danh sách bên dưới ánh xạ **114 nhóm test case**; một nhóm có thể chạy nhiều bộ tham số.
- Đã gỡ test thuần nghiệp vụ giá, giữ assertion Claim/ownership/ingestion/Compare và phân quyền.
- Thêm 12 lượt integration test cho endpoint cũ 404, response không có `rateCard`, permission
  đã gỡ và nâng cấp Vy V10 → V12 có/không có override cũ. Khôi phục riêng test claim kênh đã có owner.
- Migration test giữ nguyên giá/audit lịch sử, quyền khác và version của role không bị ảnh hưởng.
- Lượt chọn lọc ban đầu gặp lỗi Docker engine chưa chạy (33 lượt: 18 đạt, 15 lỗi khởi tạo,
  không có assertion thất bại). Sau khi Docker hoạt động, đã chạy lại bằng clean full regression
  trên PostgreSQL/pgvector Testcontainers riêng và đạt kết quả trên.
- V9 không đổi; không chạy migration lên database dùng chung. Không sửa `.env`, Bruno hoặc frontend.
- Các kết quả 357 test dưới đây là lịch sử trước khi gỡ chức năng; chưa commit/push đợt này.

## Kết quả thực thi ngày 21/10/2026

- Sau bổ sung test và sửa replay: thêm **123 lượt kiểm thử** so với baseline 234;
  tổng **357 test / 46 suite**.
- Full regression sau bản sửa: **357 đạt, 0 thất bại, 0 execution error, 0 bỏ qua**.
- BUG-AUTH-001 đã sửa và test tái hiện đã đạt. Không có lỗi Docker/PostgreSQL
  hoặc test bị tắt để che lỗi. Các test có trước vẫn được giữ và đạt.
- Regression Auth/RBAC chọn lọc sau bản sửa: **51/51 đạt**.
- Trước khi sửa, full regression 354/355 đạt và integration chọn lọc 87/88 đạt;
  lỗi duy nhất ở cả hai lần là BUG-AUTH-001, còn 2 refresh token chưa bị thu hồi.
- Đợt đầu chỉ thêm test/tài liệu; theo yêu cầu tiếp theo đã sửa transaction Auth
  và thêm exception riêng cho replay. Kết quả được ghi nhận trước khi commit/push. Không thay migration,
  `.env`, Bruno, cấu hình máy hoặc database dùng chung.
- Full regression được chạy bằng JDK 21/Maven với `-DforkCount=0` và Byte Buddy
  agent nạp trước cho Mockito trên Windows. Không đo hoặc tuyên bố tỷ lệ coverage
  JaCoCo trong lần chạy này.
- Regression sau rebase lên `develop` tại `385ace3`: **357/357 đạt**, không lỗi
  hoặc bỏ qua; đã kiểm tra cùng các cập nhật campaign autofill/ingestion mới merge.

## Phạm vi và cách đọc

Danh sách ánh xạ test tự động cho các module backend Khải phụ trách. Giữ bộ 234 test
trước đợt này; bổ sung unit và integration test theo domain. Đây là tài liệu trong
repo, chưa phải nội dung đã điền vào Report của nhóm. Không cam kết coverage 100%.

- **Unit**: JUnit 5/Mockito, kiểm tra logic và tương tác repository/service; không
  chứng minh được commit/rollback thực tế hoặc Spring filter/method security.
- **Integration**: MockMvc + PostgreSQL/pgvector Testcontainers, chạy Flyway và
  Hibernate validate; kiểm tra HTTP, quyền, database, constraint và transaction.
- Một dòng có thể là parameterized test với nhiều bộ dữ liệu. Số lượt thực thi
  trong Surefire vì vậy lớn hơn số dòng test case.
- Fixture dùng UUID/owner riêng, không phụ thuộc thứ tự. Các suite integration mới
  dùng database Docker riêng qua `PostgresApiTestSupport`; container dùng chung
  trong JVM và được Testcontainers dọn khi JVM kết thúc. Không dùng database chung,
  tài khoản thật, Google Cloud, VNPay thật hoặc Bruno.
- Các kiểm thử rollback tạo trigger gây lỗi có chủ đích trên database test và
  xóa trigger trong `finally`; không chạy những lệnh này vào database nghiệp vụ.

## Chạy kiểm thử

Yêu cầu Java 21, Maven và Docker Desktop chạy Linux containers cho integration.
Không cần chạy BE riêng hoặc chỉnh `.env`.

```powershell
# Unit theo domain
mvn '-Dtest=BrandServiceTest,CampaignServiceTest,DataQualityServiceTest,ShortlistCompareServiceTest,PaymentSettlementServiceTest,PaymentCallbackServiceTest,MockPaymentServiceTest,SubscriptionServiceTest,AuditServiceTest,AdminRoleGuardTest,AuthServiceTest,RefreshTokenServiceTest,CreatorClaimServiceTest' test

# Integration mới và các suite đã mở rộng
mvn '-Dtest=BrandPostgresIntegrationTest,CampaignPostgresIntegrationTest,AuditLogPostgresIntegrationTest,AuthSessionPostgresIntegrationTest,SubscriptionPostgresIntegrationTest,CreatorClaimPostgresIntegrationTest,RolePermissionPostgresIntegrationTest' test

# Toàn bộ regression
mvn test

# Tái hiện riêng BUG-AUTH-001
mvn '-Dtest=AuthSessionPostgresIntegrationTest#replayedRotatedTokenRevokesAllOtherSessionsEvenWhenRequestIsRejected' test
```

Xem kết quả từng class/method trong `target/surefire-reports/TEST-*.xml` hoặc file
`.txt` cùng thư mục. Trước bản sửa, test BUG-AUTH-001 làm Maven trả exit 1; sau bản
sửa test đó phải đạt. Không bỏ qua test hoặc đổi kỳ vọng để làm xanh suite.

## Lỗi nghiệp vụ phát hiện và bản sửa

### BUG-AUTH-001 — Thu hồi session khi replay refresh token bị rollback

- Test: `AuthSessionPostgresIntegrationTest#replayedRotatedTokenRevokesAllOtherSessionsEvenWhenRequestIsRejected`.
- Điều kiện: cùng một tài khoản đăng nhập hai lần, sau đó rotate refresh token
  của session thứ nhất. Còn hai refresh token hoạt động: session thứ hai và token
  thay thế của session thứ nhất.
- Thao tác: gửi lại refresh token cũ của session thứ nhất.
- Mong đợi: HTTP 401 và tất cả refresh token còn hoạt động của tài khoản được thu hồi,
  đúng với nhánh xử lý replay và unit test `rotate_FailsWhenRevokedAndRevokesAll` hiện có.
- Trước khi sửa đã tái hiện: HTTP 401 nhưng database vẫn còn **2 token chưa bị thu hồi**.
- Nguyên nhân: `RefreshTokenService.rotate()` gọi `revokeAll()` rồi ném
  `BusinessException` trong transaction; `AuthServiceImpl.refresh()` cũng có
  transaction bao ngoài. Exception dẫn tới rollback cả thao tác thu hồi. Mockito
  chỉ xác nhận lời gọi `revokeAll()`, không phát hiện rollback này.
- Ảnh hưởng: các refresh token khác vẫn có thể được dùng để lấy access token;
  cơ chế thu hồi toàn bộ session khi phát hiện replay chưa có hiệu lực bền vững.
- Bản sửa theo yêu cầu tiếp theo: thêm `RefreshTokenReplayException`, giữ nguyên
  HTTP 401/code UNAUTHENTICATED. Cả `RefreshTokenService.rotate()` và transaction
  bao ngoài `AuthServiceImpl.refresh()` dùng `noRollbackFor` chỉ với exception này,
  để commit việc thu hồi trong nhánh replay trước khi trả lỗi. Lỗi khác vẫn rollback.
- Giữ cùng transaction và khóa `rbac_guard`; không dùng REQUIRES_NEW khi transaction
  ngoài còn giữ khóa, tránh tự chặn nhau. Rotate thành công vẫn tham gia transaction
  của caller, không commit token mới độc lập.
- Test gốc giữ nguyên kỳ vọng và thêm kiểm tra token của người khác không bị ảnh
  hưởng, token bị thu hồi thực sự không refresh được; bổ sung test refresh đồng thời
  và rollback rotate thành công khi caller thất bại.
- Trạng thái: **Resolved**, đã qua test tái hiện và full regression 357/357.

Regression Auth/RBAC chọn lọc sau bản sửa: **51/51 đạt**, gồm test gốc và hai test
bổ sung về transaction/concurrency. HTTP contract và schema database không đổi.

## Danh sách test case

Tên chức năng và mô tả case dùng tiếng Anh để tiện ánh xạ code và báo cáo nhóm.
Mỗi case bên dưới chỉ rõ class/method; dữ liệu parameterized nằm ngay trong source.

### Authentication & Session Management

| ID | Type | Preconditions / Action | Expected result | Automated test |
|---|---|---|---|---|
| BE-001 | Unit | Register padded mixed-case email/name | Save trimmed lowercase email, trimmed name, BRAND and encoded password | [AuthServiceTest](../src/test/java/com/influencermatch/backend/auth/service/AuthServiceTest.java)`#registerTrimsEmailAndNameAndNeverPersistsPlainPassword` |
| BE-002 | Unit | AuthenticationManager rejects password | Reject without tokens or user save | [AuthServiceTest](../src/test/java/com/influencermatch/backend/auth/service/AuthServiceTest.java)`#invalidPasswordDoesNotGenerateAnyTokens` |
| BE-003 | Unit | Rotate token expired at fixed past timestamp | Reject; no new token or revocation of other sessions | [RefreshTokenServiceTest](../src/test/java/com/influencermatch/backend/auth/service/RefreshTokenServiceTest.java)`#expiredTokenDoesNotCreateOrRevokeOtherSessions` |
| BE-004 | Unit | Rotate/revoke unknown token; revoke already revoked token | Reject rotation; revoke is idempotent and preserves timestamp | [RefreshTokenServiceTest](../src/test/java/com/influencermatch/backend/auth/service/RefreshTokenServiceTest.java)`#unknownTokenAndIdempotentRevokeDoNotWrite` |
| BE-005 | Unit | Principal role differs from database role | Reject before refresh-token write | [RefreshTokenServiceTest](../src/test/java/com/influencermatch/backend/auth/service/RefreshTokenServiceTest.java)`#changedRoleCannotIssueTokenFromStalePrincipal` |
| BE-008 | Integration | Register mixed-case email with injected ADMIN role; repeat | Injected role 400; valid request creates unverified BRAND with encoded password and trimmed name; duplicate 409; no Brand Profile | [AuthSessionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/AuthSessionPostgresIntegrationTest.java)`#registrationNormalizesCaseAndRejectsInjectedRole` |
| BE-009 | Integration | Wrong password, then uppercase email with valid password | 401 creates no session; valid login token can call /auth/me | [AuthSessionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/AuthSessionPostgresIntegrationTest.java)`#wrongPasswordDoesNotIssueSessionAndLoginNormalizesEmail` |
| BE-010 | Integration | Login then mark user LOCKED/DISABLED | Login, refresh and old access token rejected; no extra refresh row | [AuthSessionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/AuthSessionPostgresIntegrationTest.java)`#inactiveAccountsCannotLoginOrRefresh` |
| BE-011 | Integration | Expire stored token at fixed past time; also use unknown token | 401 UNAUTHENTICATED; no new refresh rows | [AuthSessionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/AuthSessionPostgresIntegrationTest.java)`#expiredAndUnknownRefreshTokensCannotCreateSessions` |
| BE-012 | Integration | Rotate valid token, inspect DB, logout replacement, retry | Old revoked and linked to new token; logout 204; refresh 401 | [AuthSessionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/AuthSessionPostgresIntegrationTest.java)`#rotationLinksOldTokenAndLogoutRevokesReplacement` |
| BE-013 | Integration | Two sessions; rotate first; replay first token | 401 and zero active refresh tokens for that account; other account unaffected | [AuthSessionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/AuthSessionPostgresIntegrationTest.java)`#replayedRotatedTokenRevokesAllOtherSessionsEvenWhenRequestIsRejected` |
| BE-014 | Integration | Malformed Authorization header or Bearer JWT | 401 | [AuthSessionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/AuthSessionPostgresIntegrationTest.java)`#invalidAuthorizationIs401` |
| BE-108 | Integration | Rotate successfully inside caller transaction, then force caller failure | Original token remains active/unreplaced; no replacement row; original can refresh afterward | [AuthSessionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/AuthSessionPostgresIntegrationTest.java)`#successfulRotationStillRollsBackWithItsCallerTransaction` |
| BE-109 | Integration | Two concurrent refresh requests use the same token | One 200, one 401; all refresh tokens of that account revoked durably; completes without deadlock | [AuthSessionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/AuthSessionPostgresIntegrationTest.java)`#concurrentRefreshReplayCommitsRevocationWithoutDeadlock` |

### Access Control & User Management

| ID | Type | Preconditions / Action | Expected result | Automated test |
|---|---|---|---|---|
| BE-015 | Unit | Assign CREATOR to existing CREATOR with version 5 | Version unchanged; no refresh revocation/audit | [AdminRoleGuardTest](../src/test/java/com/influencermatch/backend/user/service/AdminRoleGuardTest.java)`#sameRoleDoesNotRevokeOrAudit` |
| BE-016 | Unit | CREATOR owns channel or has pending claim | CREATOR_ROLE_CHANGE_CONFLICT; role/version preserved; no revocation/audit | [AdminRoleGuardTest](../src/test/java/com/influencermatch/backend/user/service/AdminRoleGuardTest.java)`#creatorOwnershipOrPendingClaimPreventsRoleChange` |
| BE-104 | Integration | Admin changes logged-in account role | Old tokens rejected; new permissions; Manager denied private APIs | [RbacPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/security/RbacPostgresIntegrationTest.java)`#roleChangeRevokesAccessAndRefreshAndManagerCannotAccessPrivateApis` |
| BE-105 | Integration | Lock/unlock then repeat no-op | Old token never revived; no-op does not revoke valid token | [RbacPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/security/RbacPostgresIntegrationTest.java)`#lockUnlockNeverRestoresOldTokenAndNoOpDoesNotInvalidate` |
| BE-106 | Integration | Concurrent Admin removal | At least one active Admin remains | [RbacPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/security/RbacPostgresIntegrationTest.java)`#concurrentChangesCannotRemoveLastActiveAdministrator` |

### Role Permission Management

| ID | Type | Preconditions / Action | Expected result | Automated test |
|---|---|---|---|---|
| BE-017 | Integration | Disable two BRAND permissions; inspect same token/CREATOR; send mixed valid-invalid patch | One version/audit; other role/permissions unchanged; invalid batch writes nothing | [RolePermissionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/security/RolePermissionPostgresIntegrationTest.java)`#multiPermissionPatchIsAtomicAndIndependentAcrossRoles` |
| BE-018 | Integration | Disable permission then reuse token for collaboration/outcome/review/claim/claim-review/checkout | 403 before resource mutation; no new audit | [RolePermissionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/security/RolePermissionPostgresIntegrationTest.java)`#toggledBusinessOperationsAreForbiddenBeforeAnyResourceMutation` |
| BE-019 | Integration | Change permission, repeat no-op, submit stale version | Correct version/audit; no-op unchanged; stale update 409 | [RolePermissionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/security/RolePermissionPostgresIntegrationTest.java)`#versionAuditAndNoopAreConsistent` |
| BE-020 | Integration | Database rejects permission-change audit | Override/version/audit rollback together | [RolePermissionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/security/RolePermissionPostgresIntegrationTest.java)`#auditFailureRollsBackOverridesAndVersion` |
| BE-021 | Integration | Two Admins update with same version | One success and one 409 | [RolePermissionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/security/RolePermissionPostgresIntegrationTest.java)`#concurrentAdminsCannotOverwriteEachOther` |

### Brand Profile Management

| ID | Type | Preconditions / Action | Expected result | Automated test |
|---|---|---|---|---|
| BE-022 | Unit | Create with padded/duplicate categories/platforms | Normalized values; initial M4 context version 1 | [BrandServiceTest](../src/test/java/com/influencermatch/backend/brand/BrandServiceTest.java)`#createNormalizesListsAndCreatesInitialContext` |
| BE-023 | Unit | Owner already has profile | BRAND_ALREADY_EXISTS; no profile/context writes | [BrandServiceTest](../src/test/java/com/influencermatch/backend/brand/BrandServiceTest.java)`#duplicateProfileDoesNotWriteContext` |
| BE-024 | Unit | BRAND requests another owner ID | Forbidden; no profile/context writes | [BrandServiceTest](../src/test/java/com/influencermatch/backend/brand/BrandServiceTest.java)`#cannotCreateForAnotherUser` |
| BE-025 | Unit | Update profile with existing learning/evidence | M4 increments and profile JSON changes; learning/evidence preserved | [BrandServiceTest](../src/test/java/com/influencermatch/backend/brand/BrandServiceTest.java)`#updateRefreshesContextAndPreservesLearnedEvidence` |
| BE-026 | Unit | Read/update foreign profile | Forbidden; original name preserved; no context access | [BrandServiceTest](../src/test/java/com/influencermatch/backend/brand/BrandServiceTest.java)`#foreignBrandCannotReadOrUpdate` |
| BE-027 | Unit | Get unknown profile ID | BRAND_NOT_FOUND | [BrandServiceTest](../src/test/java/com/influencermatch/backend/brand/BrandServiceTest.java)`#missingProfileReturnsSpecificError` |
| BE-028 | Unit | Page 1 when owner has one profile | Empty items, total 1; no unrestricted findAll | [BrandServiceTest](../src/test/java/com/influencermatch/backend/brand/BrandServiceTest.java)`#laterListPageContainsNoForeignRows` |
| BE-029 | Integration | Create, update and list own profile with another owner present | 201/200; m4Version 1 then 2; DB JSON matches profile; no foreign rows | [BrandPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/brand/BrandPostgresIntegrationTest.java)`#createUpdateAndListKeepProfileAndContextConsistent` |
| BE-030 | Integration | Duplicate create and foreign read/update/create-for-owner | 409/403; original name/context version unchanged | [BrandPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/brand/BrandPostgresIntegrationTest.java)`#duplicateAndForeignAccessNeverChangeProfileOrContext` |
| BE-031 | Integration | Blank/overlong name, bad website or blank category | 400; no profile row for fixture owner | [BrandPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/brand/BrandPostgresIntegrationTest.java)`#invalidProfileDoesNotCreateAnyRows` |
| BE-032 | Integration | Admin reads/writes; other roles/anonymous read; unknown ID | Admin read 200/write 403; others 403/401; missing 404 | [BrandPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/brand/BrandPostgresIntegrationTest.java)`#adminHasReadOnlySupportAndOtherRolesAreDenied` |
| BE-033 | Integration | Trigger rejects context insert/update for fixture marker | 500; create leaves no profile; update preserves name/version | [BrandPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/brand/BrandPostgresIntegrationTest.java)`#contextFailureRollsBackProfileCreationAndUpdate` |

### Campaign Management

| ID | Type | Preconditions / Action | Expected result | Automated test |
|---|---|---|---|---|
| BE-034 | Unit | Create with padded/duplicate lists | DRAFT with normalized values; no premature M3 | [CampaignServiceTest](../src/test/java/com/influencermatch/backend/campaign/CampaignServiceTest.java)`#createsDraftWithNormalizedListsAndNoPrematureContext` |
| BE-035 | Unit | End before start, follower max below min, budget max below min | INVALID_CAMPAIGN_RANGE; no campaign/context writes | [CampaignServiceTest](../src/test/java/com/influencermatch/backend/campaign/CampaignServiceTest.java)`#invalidRangesDoNotPersist` |
| BE-036 | Unit | Update READY_FOR_DISCOVERY/ACTIVE/COMPLETED/ARCHIVED | CAMPAIGN_NOT_EDITABLE; name/status unchanged | [CampaignServiceTest](../src/test/java/com/influencermatch/backend/campaign/CampaignServiceTest.java)`#onlyDraftCanBeEdited` |
| BE-037 | Unit | DRAFT to READY then ACTIVE | One M3 version 1 with campaign criteria | [CampaignServiceTest](../src/test/java/com/influencermatch/backend/campaign/CampaignServiceTest.java)`#readyTransitionCreatesOneContextAndSequentialActivationDoesNotRecreateIt` |
| BE-038 | Unit | DRAFT without platforms requests READY | CAMPAIGN_CONTEXT_NOT_READY; DRAFT preserved; no M3 | [CampaignServiceTest](../src/test/java/com/influencermatch/backend/campaign/CampaignServiceTest.java)`#missingPlatformsCannotCreateDiscoveryContext` |
| BE-039 | Unit | Archive already archived; read unknown ID | ARCHIVED unchanged; CAMPAIGN_NOT_FOUND | [CampaignServiceTest](../src/test/java/com/influencermatch/backend/campaign/CampaignServiceTest.java)`#archiveIsIdempotentAndMissingCampaignIs404` |
| BE-040 | Unit | Brand access service rejects ownership | Forbidden before name/status/context mutation | [CampaignServiceTest](../src/test/java/com/influencermatch/backend/campaign/CampaignServiceTest.java)`#ownershipFailureHappensBeforeMutation` |
| BE-041 | Integration | Create/update DRAFT then READY to ACTIVE to COMPLETED; list | Correct states/normalization; one M3; Brand Context unchanged | [CampaignPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/campaign/CampaignPostgresIntegrationTest.java)`#fullLifecycleCreatesOneContextAndPreservesProfile` |
| BE-042 | Integration | Invalid ranges, negative budget, missing platforms, blank name | 400 and range error code when applicable; no campaign rows | [CampaignPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/campaign/CampaignPostgresIntegrationTest.java)`#invalidCreateDoesNotWriteCampaignOrContext` |
| BE-043 | Integration | Foreign Brand, Admin read/write, anonymous, unknown ID | 403 foreign/admin write; 200 Admin read; 401 anonymous; 404 missing | [CampaignPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/campaign/CampaignPostgresIntegrationTest.java)`#ownershipAndReadOnlyAdminAreEnforced` |
| BE-044 | Integration | Skip DRAFT to ACTIVE; archive twice; edit archived | Skip/edit 409; archive 200 twice; no M3 | [CampaignPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/campaign/CampaignPostgresIntegrationTest.java)`#archiveIsIdempotentAndRejectedTransitionsDoNotWrite` |
| BE-045 | Integration | Trigger rejects initial M3 insert | 500; remain DRAFT with no context | [CampaignPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/campaign/CampaignPostgresIntegrationTest.java)`#contextInsertFailureRollsBackReadyTransition` |

### Creator Ownership Verification

| ID | Type | Preconditions / Action | Expected result | Automated test |
|---|---|---|---|---|
| BE-046 | Unit | Verified Creator claims missing channel | NotFound; no claim/audit writes | [CreatorClaimServiceTest](../src/test/java/com/influencermatch/backend/creator/CreatorClaimServiceTest.java)`#missingChannelNeverCreatesClaim` |
| BE-047 | Unit | Reviewer reads missing claim | NotFound; no audit or claim save | [CreatorClaimServiceTest](../src/test/java/com/influencermatch/backend/creator/CreatorClaimServiceTest.java)`#missingReviewTargetNeverAudits` |
| BE-048 | Integration | Claim for missing channel; reviewer reads/revokes missing claim | Three 404 responses; no new audit/claim rows | [CreatorClaimPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/CreatorClaimPostgresIntegrationTest.java)`#missingChannelAndClaimReturn404WithoutAudits` |
| BE-049 | Integration | Competing claims, approval, ingestion, revoke | One owner; ingestion preserves ownership; revoke clears ownership and verification | [CreatorClaimPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/CreatorClaimPostgresIntegrationTest.java)`#completeFlowCompetingClaimsIngestionAndRevocation` |
| BE-050 | Integration | Another Creator/profile reader requests evidence | Evidence remains private | [CreatorClaimPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/CreatorClaimPostgresIntegrationTest.java)`#claimEvidenceIsPrivateAcrossCreatorsAndPublicProfile` |
| BE-051 | Integration | Concurrent competing claim approvals | Exactly one approved owner | [CreatorClaimPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/CreatorClaimPostgresIntegrationTest.java)`#concurrentReviewsHaveOneWinner` |

### Data Quality Review

| ID | Type | Preconditions / Action | Expected result | Automated test |
|---|---|---|---|---|
| BE-052 | Unit | Approve low-confidence KOC with padded new Niche | Trim Niche; preserve confidence; reviewer/time saved; creator lock precedes metric; flush then audit | [DataQualityServiceTest](../src/test/java/com/influencermatch/backend/creator/DataQualityServiceTest.java)`#approvalKeepsConfidenceAndLocksCreatorBeforeSnapshot` |
| BE-053 | Unit | KOL, null/0.7 confidence, reviewed or stale snapshot | DATA_QUALITY_REVIEW_CONFLICT; no mutation/flush/audit | [DataQualityServiceTest](../src/test/java/com/influencermatch/backend/creator/DataQualityServiceTest.java)`#ineligibleSnapshotDoesNotChangeOrAudit` |
| BE-054 | Unit | Whitespace Niche, then unknown metric with valid body | Validation before lookup; NotFound for missing metric; no audit | [DataQualityServiceTest](../src/test/java/com/influencermatch/backend/creator/DataQualityServiceTest.java)`#invalidNicheFailsBeforeLookupAndMissingMetricIs404` |
| BE-055 | Unit | Negative page and size 1000 | Repository receives page 0 and size 100 | [DataQualityServiceTest](../src/test/java/com/influencermatch/backend/creator/DataQualityServiceTest.java)`#pendingClampsPageAndSize` |
| BE-056 | Integration | Historical/latest snapshots, confidence boundary and timestamp ties | Only eligible latest KOC; stable UUID tie-break | [DataQualityPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/DataQualityPostgresIntegrationTest.java)`#filtersLatestBeforeApplyingThresholdAndUsesUuidTieBreak` |
| BE-057 | Integration | Concurrent review of same metric | One approval and one conflict | [DataQualityPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/DataQualityPostgresIntegrationTest.java)`#concurrentApprovalHasOneWinner` |
| BE-058 | Integration | Ingestion overlaps review | Recheck latest snapshot under creator lock | [DataQualityPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/DataQualityPostgresIntegrationTest.java)`#ingestionLockForcesApprovalToRecheckLatestSnapshot` |
| BE-059 | Integration | Audit insert fails during approval | Review fields and Niche rollback | [DataQualityPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/DataQualityPostgresIntegrationTest.java)`#auditFailureRollsBackApprovalAndNiche` |

### Shortlist Comparison / Creator Relationships

| ID | Type | Preconditions / Action | Expected result | Automated test |
|---|---|---|---|---|
| BE-060 | Unit | Repository order differs; metrics missing; no valid ownership | Request order preserved; null metrics; verification false; one batch read; no saves | [ShortlistCompareServiceTest](../src/test/java/com/influencermatch/backend/relationship/ShortlistCompareServiceTest.java)`#batchesReadsPreservesOrderAndMissingMetricsRemainNull` |
| BE-061 | Unit | Compare another owner's campaign | NotFound before membership/metrics queries | [ShortlistCompareServiceTest](../src/test/java/com/influencermatch/backend/relationship/ShortlistCompareServiceTest.java)`#foreignCampaignIsHiddenBeforeReadingMembership` |
| BE-062 | Unit | Duplicate IDs, then non-shortlisted IDs | Validation errors; no metrics queries or saves | [ShortlistCompareServiceTest](../src/test/java/com/influencermatch/backend/relationship/ShortlistCompareServiceTest.java)`#duplicateAndMissingMembersNeverReadMetrics` |
| BE-063 | Unit | Empty comparison selection | Constraint violation before any repository call | [ShortlistCompareServiceTest](../src/test/java/com/influencermatch/backend/relationship/ShortlistCompareServiceTest.java)`#invalidSelectionFailsBeforeAnyRepositoryAccess` |
| BE-100 | Integration | Compare shortlist with snapshots and verification | Request order/latest snapshot; verification only for authorized owner; no rateCard field | [ComparePaymentPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/ComparePaymentPostgresIntegrationTest.java)`#comparePreservesOrderLatestMetricsAndVerification` |
| BE-101 | Integration | Cross-Brand item/relationship access | Ownership enforced without mutation | [ComparePaymentPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/ComparePaymentPostgresIntegrationTest.java)`#anotherBrandCannotReadOrMutateShortlistAndRelationships` |
| BE-102 | Integration | Reference other campaign or Creator's item | Invalid linkage rejected | [ComparePaymentPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/ComparePaymentPostgresIntegrationTest.java)`#relationshipCannotAttachForeignOrMismatchedShortlistItem` |
| BE-103 | Integration | Repeated/conflicting mock callbacks | Stable terminal dates/quota; one transition audit | [ComparePaymentPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/ComparePaymentPostgresIntegrationTest.java)`#terminalPaymentOutcomePreservesDatesQuotasAndSingleAudit` |

### Payment & Subscription Management

| ID | Type | Preconditions / Action | Expected result | Automated test |
|---|---|---|---|---|
| BE-064 | Unit | Lock existing payment | Brand lock before Payment lock and refresh | [PaymentSettlementServiceTest](../src/test/java/com/influencermatch/backend/billing/PaymentSettlementServiceTest.java)`#locksBrandBeforePaymentAndRefreshesLockedEntity` |
| BE-065 | Unit | Settle PENDING successfully | PAID/ACTIVE; activation dates/duration correct; quotas preserved | [PaymentSettlementServiceTest](../src/test/java/com/influencermatch/backend/billing/PaymentSettlementServiceTest.java)`#successUsesActivationDateAndPreservesQuota` |
| BE-066 | Unit | Settle PENDING as failed | FAILED/CANCELLED with reason; dates/quota preserved | [PaymentSettlementServiceTest](../src/test/java/com/influencermatch/backend/billing/PaymentSettlementServiceTest.java)`#failureCancelsWithoutChangingDatesOrQuota` |
| BE-067 | Unit | PAID/FAILED/REFUNDED with success/failure outcome | Compatible repeat only; terminal state/data unchanged; no writes | [PaymentSettlementServiceTest](../src/test/java/com/influencermatch/backend/billing/PaymentSettlementServiceTest.java)`#terminalStatesNeverRewriteDatesOrQuota` |
| BE-068 | Unit | Another ACTIVE subscription exists | Reject activation; PENDING state unchanged | [PaymentSettlementServiceTest](../src/test/java/com/influencermatch/backend/billing/PaymentSettlementServiceTest.java)`#activeSubscriptionPreventsActivationWithoutMutatingPendingPayment` |
| BE-069 | Unit | Payment pending but subscription CANCELLED | Reject without persistence | [PaymentSettlementServiceTest](../src/test/java/com/influencermatch/backend/billing/PaymentSettlementServiceTest.java)`#nonPendingSubscriptionCannotBeActivated` |
| BE-070 | Unit | Transaction belongs to another Brand | NotFound before settlement/audit | [MockPaymentServiceTest](../src/test/java/com/influencermatch/backend/billing/MockPaymentServiceTest.java)`#foreignPaymentIsHiddenBeforeAcquiringLocks` |
| BE-071 | Unit | Blank or unknown transaction | Validation/NotFound; no settlement/audit | [MockPaymentServiceTest](../src/test/java/com/influencermatch/backend/billing/MockPaymentServiceTest.java)`#missingAndInvalidRequestsDoNotSettle` |
| BE-072 | Unit | Shared settlement rejects outcome | SUBSCRIPTION_CONFLICT; no audit | [MockPaymentServiceTest](../src/test/java/com/influencermatch/backend/billing/MockPaymentServiceTest.java)`#conflictingOutcomeDoesNotAudit` |
| BE-073 | Unit | Successful transition then repeated SUCCESS | PAID response; exactly one audit | [MockPaymentServiceTest](../src/test/java/com/influencermatch/backend/billing/MockPaymentServiceTest.java)`#successfulStateChangeAuditsOnceAndRepeatedOutcomeDoesNotAuditAgain` |
| BE-074 | Unit | Missing/short signature, tampered amount or signed wrong merchant | Reject before payment lookup/lock | [PaymentCallbackServiceTest](../src/test/java/com/influencermatch/backend/billing/PaymentCallbackServiceTest.java)`#invalidSignatureOrMerchantNeverLocksPayment` |
| BE-075 | Unit | Valid signature/merchant; DB amount mismatch | Reject without settlement apply | [PaymentCallbackServiceTest](../src/test/java/com/influencermatch/backend/billing/PaymentCallbackServiceTest.java)`#signedWrongAmountDoesNotSettle` |
| BE-076 | Unit | Unknown signed transaction then signed gateway failure | Unknown rejected; failure delegates exact code to settlement | [PaymentCallbackServiceTest](../src/test/java/com/influencermatch/backend/billing/PaymentCallbackServiceTest.java)`#signedGatewayFailureUsesSharedSettlementAndUnknownTransactionIsRejected` |
| BE-077 | Unit | Owner subscribes free active plan | ACTIVE, correct duration, zero quota usage; no Admin audit | [SubscriptionServiceTest](../src/test/java/com/influencermatch/backend/billing/SubscriptionServiceTest.java)`#freePlanInitializesDatesAndZeroQuota` |
| BE-078 | Unit | Brand subscribes paid plan directly | PAID_PLAN_REQUIRES_PAYMENT; no save/audit | [SubscriptionServiceTest](../src/test/java/com/influencermatch/backend/billing/SubscriptionServiceTest.java)`#paidPlanCannotBeActivatedDirectlyByBrand` |
| BE-079 | Unit | Inactive plan then existing active subscription | Conflict without subscription save/audit | [SubscriptionServiceTest](../src/test/java/com/influencermatch/backend/billing/SubscriptionServiceTest.java)`#inactivePlanAndExistingActiveSubscriptionAreConflicts` |
| BE-080 | Unit | Read foreign Brand subscription list | Forbidden before subscription query | [SubscriptionServiceTest](../src/test/java/com/influencermatch/backend/billing/SubscriptionServiceTest.java)`#foreignBrandCannotReadSubscriptions` |
| BE-081 | Unit | Admin cancels twice | CANCELLED; dates/quota preserved; one audit | [SubscriptionServiceTest](../src/test/java/com/influencermatch/backend/billing/SubscriptionServiceTest.java)`#cancellationPreservesQuotaAndAdminNoopDoesNotAudit` |
| BE-082 | Integration | Free subscribe, duplicate, foreign read/cancel, cancel twice | ACTIVE; duplicate 409; foreign 403; one CANCELLED row | [SubscriptionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/billing/SubscriptionPostgresIntegrationTest.java)`#freeSubscriptionOwnershipDuplicateAndCancellation` |
| BE-083 | Integration | Brand direct paid subscribe; Admin grant/cancel twice | Brand 409; Admin succeeds; one create and one cancel audit | [SubscriptionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/billing/SubscriptionPostgresIntegrationTest.java)`#paidPlanRequiresCheckoutButAdminCanGrantAndCancelWithAudit` |
| BE-084 | Integration | Deactivate plan; Brand/Admin read; Brand subscribes | Brand read 404; Admin read 200; subscribe 409 with no new row | [SubscriptionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/billing/SubscriptionPostgresIntegrationTest.java)`#inactivePlanHiddenFromBrandAndCannotBeSubscribed` |

### Audit Log

| ID | Type | Preconditions / Action | Expected result | Automated test |
|---|---|---|---|---|
| BE-085 | Unit | Authenticated Admin records change with request IP | Actor/action/entity, JSON before/after, reason, IP and timestamp persisted | [AuditServiceTest](../src/test/java/com/influencermatch/backend/audit/AuditServiceTest.java)`#recordsActorEntityPayloadAndRequestAddress` |
| BE-086 | Unit | No request bound; authenticated actor; null payloads | Null IP and JSON null payloads | [AuditServiceTest](../src/test/java/com/influencermatch/backend/audit/AuditServiceTest.java)`#backgroundInvocationHasNoRequestIpAndAcceptsNullPayloads` |
| BE-087 | Unit | No User authentication | Reject without save | [AuditServiceTest](../src/test/java/com/influencermatch/backend/audit/AuditServiceTest.java)`#unauthenticatedActorCannotCreateAudit` |
| BE-088 | Integration | Fixtures vary actor/action/entity/time; combine filters with equal from/to | Exactly matching event; inclusive boundary and correct JSON | [AuditLogPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/audit/AuditLogPostgresIntegrationTest.java)`#combinesAllFiltersAndIncludesTimeBoundaries` |
| BE-089 | Integration | Distinct timestamps, size 2 and invalid page/size bounds | Newest first; correct totals; page minimum 0, size 1-100 | [AuditLogPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/audit/AuditLogPostgresIntegrationTest.java)`#paginationOrdersLatestFirstAndCapsSize` |
| BE-090 | Integration | Reversed dates, malformed date/UUID | 400; no new audit | [AuditLogPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/audit/AuditLogPostgresIntegrationTest.java)`#invalidFiltersAre400AndDoNotCreateAudit` |
| BE-091 | Integration | BRAND/CREATOR/DATA_MANAGER requests logs | 403 | [AuditLogPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/audit/AuditLogPostgresIntegrationTest.java)`#nonAdminCannotReadAudit` |
| BE-092 | Integration | Anonymous requests logs | 401 | [AuditLogPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/audit/AuditLogPostgresIntegrationTest.java)`#anonymousCannotReadAudit` |
| BE-093 | Integration | Trigger rejects status audit after account login | 500; ACTIVE/version/refresh preserved; old access token works; no audit | [AuditLogPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/audit/AuditLogPostgresIntegrationTest.java)`#auditFailureRollsBackUserStatusVersionAndRefreshRevocation` |

### Collaboration Management / Campaign Outcome Tracking / Creator Reviews

| ID | Type | Preconditions / Action | Expected result | Automated test |
|---|---|---|---|---|
| BE-094 | Unit | Owner closes eligible relationship | Agreed fee persisted; relationship stage synchronized | [CollaborationServiceTest](../src/test/java/com/influencermatch/backend/collaboration/CollaborationServiceTest.java)`#createsAndSynchronizesFeeAndStage` |
| BE-095 | Unit | Valid outcome views/clicks/post URL | Collaboration completed; payment unchanged | [CollaborationServiceTest](../src/test/java/com/influencermatch/backend/collaboration/CollaborationServiceTest.java)`#outcomeCompletesOnlyCollaborationAndPreservesPayment` |
| BE-096 | Unit | Review completed collaboration | Review links authenticated owner and actual outcome | [CollaborationServiceTest](../src/test/java/com/influencermatch/backend/collaboration/CollaborationServiceTest.java)`#reviewUsesAuthenticatedOwnerAndActualOutcome` |
| BE-097 | Integration | Invalid fees, metrics, URL or review input | Reject without persisted result | [CollaborationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/collaboration/CollaborationPostgresIntegrationTest.java)`#validatesMoneyMetricsUrlStarsAndRequiredBoolean` |
| BE-098 | Integration | Collaboration transaction fails | Relationship/collaboration rollback together | [CollaborationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/collaboration/CollaborationPostgresIntegrationTest.java)`#transactionRollbackRestoresRelationshipAndCollaboration` |
| BE-099 | Integration | Concurrent duplicate create/outcome/review | Exactly one resource of each type | [CollaborationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/collaboration/CollaborationPostgresIntegrationTest.java)`#concurrentRequestsCreateExactlyOneOfEachResource` |

### Retired Creator Rate Card Regression

| ID | Level | Condition / action | Expected | Automated test |
| --- | --- | --- | --- | --- |
| BE-110 | Integration | All roles call removed GET/PUT endpoints | 404; no business writes | [RetiredRateCardPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/RetiredRateCardPostgresIntegrationTest.java)`#retiredEndpointsAre404WithoutBusinessWrites` |
| BE-111 | Integration | Claim, approve, compare and revoke without Rate Card storage | Verification works; no rateCard JSON; retired table absent | [RetiredRateCardPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/RetiredRateCardPostgresIntegrationTest.java)`#claimRevokeAndCompareWorkWithoutRateCardStorage` |
| BE-112 | Integration | Read permission catalog/auth and submit retired permission | Permission absent; update rejected | [RetiredRateCardPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/RetiredRateCardPostgresIntegrationTest.java)`#retiredPermissionIsAbsentAndCannotBeConfigured` |
| BE-113 | Integration | Upgrade Vy V10 to V12 with/without retired override | Prices/audits retained; override removed; only affected role version increases | [RetiredRateCardPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/RetiredRateCardPostgresIntegrationTest.java)`#upgradePreservesLegacyDataAndOnlyBumpsAffectedConfiguration` |
| BE-114 | Integration | Claim already authorized channel | 409; existing owner retained | [CreatorClaimPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/CreatorClaimPostgresIntegrationTest.java)`#authorizedChannelRejectsNewClaims` |


## Registration invitations and OTP — current implementation

BE-006, BE-007 and BE-107 were retired with Google Login. Historical counts above describe previous revisions, not a new execution. Tests named `CreatorClaimOAuthIntegrationTest` use Mockito; actual database integration is in the Postgres suites.

Each entry below specifies a condition/action, expected result, and executable mapping. SMTP and time are mocked; PostgreSQL suites use disposable pgvector containers.

- **REG-001 · Registration · Unit:** valid Brand request → unverified BRAND, BCrypt hash, initial verification call; `AuthServiceTest#registerTrimsEmailAndNameAndNeverPersistsPlainPassword`.
- **REG-002 · OTP · Unit:** issue `000042` → retain six digits, store keyed hash with ten-minute expiry; `EmailVerificationServiceTest#initialDeliveryKeepsLeadingZerosAndOnlyStoresKeyedHash`.
- **REG-003 · OTP · Unit:** incorrect code at attempts 0–4 → increment counter, fifth returns rate limit; `EmailVerificationServiceTest#wrongAttemptIsCountedAndFifthLocksCode`.
- **REG-004 · OTP policy · Unit:** expiry, consumed code, cooldown and purpose separation → reject without granting verification; `OtpChallengePolicyTest`, `EmailVerificationServiceTest`.
- **REG-005 · Invitation · Unit:** eligible email → 48-hour link sent, token omitted from response/audit; `CreatorRegistrationInvitationServiceTest#invitationNormalizesEmailStoresHashAndAuditsOnlyMetadata`.
- **REG-006 · Invitation · Unit:** existing account, exhausted budget or invalid token → no account creation or replacement; `CreatorRegistrationInvitationServiceTest#existingAccountIsNeverConvertedOrEmailed`, `#rateLimitNeverReplacesToken`, `#invalidInvitationNeverCreatesAccount`.
- **REG-007 · SMTP · Unit:** missing settings or JavaMailSender failure → 503; invitation/OTP messages use mocked transport; `SmtpVerificationEmailSenderTest`.
- **REG-008 · Brand registration · PostgreSQL integration:** register, attempt login/JWT before verification, verify and login → 403/401 before OTP, successful session afterward, no tokens in registration; `EmailVerificationPostgresIntegrationTest#registrationMustBeVerifiedBeforeLoginAndTokensAreNeverLeaked`.
- **REG-009 · OTP · PostgreSQL integration:** wrong code repeatedly → persisted attempts even on HTTP error, correct code blocked after five failures; `EmailVerificationPostgresIntegrationTest#failedAttemptsCommitAndFifthAttemptLocksEvenTheCorrectCode`.
- **REG-010 · OTP resend · PostgreSQL integration:** cooldown, resend replacement, rolling hour budget and exact expiry → enforce boundaries without sleeps; `EmailVerificationPostgresIntegrationTest#resendCooldownAndReplacementInvalidateOldCode`, `#fiveEmailsPerRollingHourIncludesRegistration`, `#codeExpiresAtTenMinutesAndCanBeReplacedWithoutSleep`.
- **REG-011 · Email rollback · PostgreSQL integration:** configuration/send/database failure → account or replacement OTP rolled back; `EmailVerificationPostgresIntegrationTest#registrationFailureRollsBackAccountAndCode`, `#smtpFailureDuringResendRollsBackHashCountersAndDelivery`, `#verificationDatabaseFailureRollsBackConsumptionAndAccountFlag`.
- **REG-012 · Verification concurrency · PostgreSQL integration:** two verifies → one consumes code; concurrent Admin role change → verification and role both retained; `EmailVerificationPostgresIntegrationTest#concurrentVerificationConsumesCodeOnlyOnce`, `#concurrentAdminRoleChangeCannotOverwriteSuccessfulVerification`.
- **REG-013 · Access control · PostgreSQL integration:** Admin role/status changes and crafted refresh cannot bypass pending email verification; `EmailVerificationPostgresIntegrationTest#pendingAccountCannotBeActivatedByAdminStatusOrRole`, `#existingAccountsRemainVerifiedButRefreshCannotBypassVerification`.
- **REG-014 · Creator registration · PostgreSQL integration:** invite → resolve → register → OTP → login → manual Claim → approve; no ownership before approval and no Google identity required; `CreatorRegistrationPostgresIntegrationTest#creatorInvitationRegistrationOtpLoginAndClaimCompleteWithoutGoogle`.
- **REG-015 · Invitation RBAC · PostgreSQL integration:** ADMIN/DATA_MANAGER allowed, BRAND/CREATOR denied; disabled dynamic permission denied; `CreatorRegistrationPostgresIntegrationTest#adminsAndDataManagersCanInviteWithoutTokensInResponse`, `#otherRolesCannotInvite` and invitation permission regression in the same suite.
- **REG-016 · Invitation lifecycle · PostgreSQL integration:** bad, expired, consumed or replaced tokens and client email/role injection → reject without account mutation; `CreatorRegistrationPostgresIntegrationTest` token/payload parameterized cases.
- **REG-017 · Invitation rollback/concurrency · PostgreSQL integration:** audit/SMTP failure leaves token usable; simultaneous registration creates one account; `CreatorRegistrationPostgresIntegrationTest#registrationSmtpFailureLeavesInvitationUsableAndNoAccount`, `#concurrentRegistrationConsumesInvitationOnce` and invitation audit rollback case.
- **REG-018 · Password setup · PostgreSQL integration:** eligible historical Google account sets first password → authVersion increments, refresh revoked, no new session; other accounts receive generic request response; `CreatorRegistrationPostgresIntegrationTest#legacyGoogleAccountSetsPasswordAndRevokesOldSessions`, `#ineligiblePasswordSetupIsGenericAndDoesNotSend`.
- **REG-019 · Password setup · PostgreSQL integration:** wrong attempts persist, purpose/expiry/budget enforced, DB failure rolls back password/token updates; `CreatorRegistrationPostgresIntegrationTest#passwordWrongAttemptsPersistAndResendResetsWithNewPurposeBoundCode`, `#passwordCodeCannotBeUsedForRegistrationAndExpiresAtTenMinutes`, `#passwordSetupSharesHourlyDeliveryBudget` and password rollback case.
- **REG-020 · Migration · PostgreSQL integration:** V12→V13 retains old verified accounts; V13→V14 retains identity, Master ownership and OTP; `EmailVerificationPostgresIntegrationTest#v13UpgradeGrandfathersExistingAccountsAndAddsVerificationStorage`, `CreatorRegistrationPostgresIntegrationTest#v14UpgradePreservesAccountsIdentitiesOwnershipAndExistingOtp`.
- **REG-021 · Google retirement · MockMvc/PostgreSQL integration:** authenticated old login/exchange paths → 404; `AuthControllerTest#googleLoginAndExchangeAreRemoved`, `CreatorRegistrationPostgresIntegrationTest#removedGoogleEndpointsAre404ForAuthenticatedUsers`.
- **REG-022 · Claim security · Unit:** anonymous, unverified or wrong invitation email → reject without creating users/Masters or consuming the invitation; `CreatorClaimResolutionServiceTest#anonymousCallbackCannotCreateAnAccount`, `#unverifiedCreatorCannotUseCallback`, `#callbackCannotConsumeInvitationSentToAnotherEmail`.
- **REG-023 · Provider simulation · Unit:** disabled flag, prod, dev+prod, test or no active profile → 503; explicit dev only → simulation available; `OAuthProofClientConfigurationTest`.
- **REG-024 · Claim boundary · PostgreSQL integration:** callback without JWT → 401; Brand → 403; Creator start with mock disabled → 503 and no Master created; `CreatorRegistrationPostgresIntegrationTest#claimCallbackRequiresSessionAndCannotBootstrapAccount`.
- **REG-025 · Regression fixtures · PostgreSQL integration:** retain manual review/revoke, compare, retired Rate Card preservation, RBAC, Collaboration and session assertions using persisted CreatorMaster; associated existing domain suites.

## Schema cleanup V15

- **SCHEMA-001 · PostgreSQL integration:** V14 upgrade with null/whitespace, existing values, independent latest values, UUID ties, no snapshots, blank history or only one missing field → backfill only the missing Creator fields and retain all remaining metric data/search_vector; `RetiredSchemaPostgresIntegrationTest#backfillOnlyMissingFieldsFromLatestNonblankSnapshot`.
- **SCHEMA-002 · PostgreSQL integration:** populated V14 auth, OTP, identities, both invitations, Master/Claim, audits and monetary agreements → V15 removes only retired data; retained rows match exactly; `RetiredSchemaPostgresIntegrationTest#upgradeRemovesOnlyRetiredDataAndPreservesAuthOwnershipInvitationsAuditAndFees`.
- **SCHEMA-003 · PostgreSQL integration:** empty schema migrates to V15 → retired tables/columns absent and current auth/ownership tables present; `RetiredSchemaPostgresIntegrationTest#freshSchemaHasOnlyTheCurrentTablesAndColumns`.
- **SCHEMA-004 · PostgreSQL API integration:** ingest/update current profile fields, filter old/new location, read profile, Campaign Discovery and Compare → unchanged wire names and current Creator values; `RetiredSchemaPostgresIntegrationTest#ingestionAndProfileComparisonUseCreatorFieldsAndLocationFilterJoinsCreator`.
- **SCHEMA-005 · Regression:** Rate Card endpoint/profile/Compare tests use current schema; V12 preservation tests stop at V12, V14 identity/exchange preservation tests stop at V14. V14→V15 cleanup/backfill tests explicitly stop at V15; fresh schema tests include V16 and both outbox tables. Auth, OTP, Claim, Billing, ownership and collaboration suites remain active.


## Claim Invitation & Conflict MVP

The claim invitation domain is distinct from Creator registration invitations. Real SMTP transport is exercised only through mocks; provider verification remains dev simulation. No new migration is required.

- **INV-001 · PostgreSQL API:** Brand with own profile/campaign sends seven-day link; HTTP 200 omits rawToken, stores only hash, verify returns public channel data without mutation. `ClaimInvitationPostgresIntegrationTest#sendsRealTransportContractWithoutTokenOrRegistrationSideEffects`.
- **INV-002 · PostgreSQL API:** unknown email receives claim invitation but cannot register using that token. `ClaimInvitationPostgresIntegrationTest#unknownRecipientGetsClaimInvitationButCannotRegisterFromIt`.
- **INV-003 · PostgreSQL API:** both invite endpoints reject owned account with 409 and persisted audit; no invitation/email writes or other-owner disclosure. `ClaimInvitationPostgresIntegrationTest#ownedChannelReturns409AndDurableAudit`.
- **INV-004 · PostgreSQL API:** pending invitation from any Brand/email blocks replacement and keeps original token usable. `ClaimInvitationPostgresIntegrationTest#duplicatePendingIsGlobalAndDoesNotReplaceOrSend`.
- **INV-005 · PostgreSQL API:** exact expiration or earlier rejects verify but permits a new send, using JPA timestamps and deterministic Clock. `ClaimInvitationPostgresIntegrationTest#expiredAtBoundaryDoesNotBlockNewInvitation`.
- **INV-006 · PostgreSQL API:** role, campaign/profile ownership and disabled dynamic permission deny before SMTP. `ClaimInvitationPostgresIntegrationTest#onlyBrandMayInvite`, `#brandMustOwnProfileCampaignAndEffectivePermission`.
- **INV-007 · PostgreSQL concurrency:** simultaneous Brands → one invitation/send, one 409 and rejection audit. `ClaimInvitationPostgresIntegrationTest#simultaneousInvitationsOnlySendOnce`.
- **INV-008 · PostgreSQL transaction:** missing SMTP configuration → 503 and rollback; SMTP failure after commit → invitation remains PENDING and outbox schedules retry. `ClaimInvitationPostgresIntegrationTest#configurationFailureRollsBackButSmtpFailureAfterCommitKeepsInvitation`.
- **INV-009 · PostgreSQL concurrency:** two callbacks and later replay → one accepted claim/Master/verification/success audit; other attempts 409 without provider re-exchange. `ClaimInvitationPostgresIntegrationTest#twoCallbacksConsumeInvitationAndCreateMasterOnce`.
- **INV-010 · PostgreSQL API:** wrong email, expired/used token or newly owned account cannot claim and do not reach provider/write tokens. `ClaimInvitationPostgresIntegrationTest#wrongEmailExpiredAndUsedInvitationNeverReachProvider`, `#ownershipAppearingAfterInviteBlocksCallbackAndManualClaimWithAudit`.
- **INV-011 · PostgreSQL API:** link channel owned by another Master → 409 + durable rejection audit, no conflict queue; same-owner link still refreshes proof. `ClaimInvitationPostgresIntegrationTest#linkingOtherOwnersChannelReturns409WithoutConflictQueueOrTokens`, `#repeatedSameOwnerLinkRetainsOwnershipAndDoesNotCreateConflict`.
- **INV-012 · PostgreSQL API:** active invitation restricts manual evidence to matching email; review consumes invitation and revoke remains functional. `ClaimInvitationPostgresIntegrationTest#invitationEmailIsEnforcedForManualEvidenceAndReviewConsumesMatchingInvitation`.
- **INV-013 · PostgreSQL rollback:** rejection audit failure → 500 and no business writes; successful callback audit failure rolls back Master/owner/verification/invitation consumption. `ClaimInvitationPostgresIntegrationTest#auditFailureRollsBackRejectionWithoutChangingBusinessData`, `#callbackAuditFailureRollsBackInvitationOwnerVerificationAndMaster`.
- **INV-014 · PostgreSQL API:** all authenticated roles get 404 on retired transfer endpoint; historical conflict data is retained. `ClaimInvitationPostgresIntegrationTest#retiredTransferEndpointIs404ForAuthenticatedAccounts`.
- **INV-015 · Log/SMTP contract:** invitation link/token absent from invitation service logs; seven-day email explains separate registration. `ClaimInvitationPostgresIntegrationTest#invitationTokenIsNotWrittenToApplicationLogs`, `SmtpVerificationEmailSenderTest#claimInvitationHasSevenDayLimitAndDoesNotImplyRegistration`.
- **INV-016 · Unit regression:** keep creator/account/proof/SMTP assertions, adapt invitation transport and pre-lock fixtures; verify expiry read does not mutate status. `CreatorInvitationServiceTest`, `CreatorClaimResolutionServiceTest`, `CreatorClaimOAuthIntegrationTest`, `CreatorAccountLinkServiceTest`, `CreatorAccountLinkIntegrationTest`, `CreatorClaimServiceTest`.

Mockito suites named Integration above do not validate database locks; INV-007/009 use actual concurrent HTTP requests and PostgreSQL locks. Historical direct-service tests of conflict resolution do not expose a transfer HTTP endpoint in MVP.

## Creator Claim Concurrency hardening

- **CONC-001 · PostgreSQL concurrency:** two callbacks on one invitation return one 200 and one 409 `INVITATION_ALREADY_PROCESSED`, with one Master/verification/success audit and one provider exchange; subsequent replay preserves full persisted claim rows, including encrypted tokens and timestamps. `ClaimInvitationPostgresIntegrationTest#twoCallbacksConsumeInvitationAndCreateMasterOnce`.
- **CONC-002 · PostgreSQL concurrency:** same Creator claims two different channels simultaneously; both return 200, share exactly one Master, and each has one accepted invitation/verification/success audit. `ClaimInvitationPostgresIntegrationTest#simultaneousDifferentChannelsReuseOneMasterForSameCreator`.
- **CONC-003 · PostgreSQL API, parameterized:** expiration at the fixed Clock or one second earlier returns 409 `INVITATION_EXPIRED`; all claim state is unchanged and provider is never called. `ClaimInvitationPostgresIntegrationTest#expiredCallbackPreservesAllClaimData`.
- **CONC-004 · PostgreSQL API:** previously used invitation returns 409 `INVITATION_ALREADY_PROCESSED`, without provider calls or persisted changes. `ClaimInvitationPostgresIntegrationTest#usedInvitationPreservesAllClaimDataWithoutReachingProvider`.
- **CONC-005 · PostgreSQL rollback:** audit failure preserves full pre-callback state, including absent Master/verification/provider identity, and leaves invitation reusable after the failure is removed. `ClaimInvitationPostgresIntegrationTest#callbackAuditFailureRollsBackInvitationOwnerVerificationAndMaster`.
- **CONC-006 · Mockito unit, parameterized:** refreshed User role/status/email verification or invitation email/platform/status/expiry must be rechecked before writes; verifies User → Account → Invitation lock/refresh ordering. This is a logic test, not proof of database concurrency. `CreatorClaimResolutionServiceTest#rechecksRefreshedStateUnderLocksBeforeAnyBusinessWrites`.

Concurrent PostgreSQL cases synchronize request workers with ready/start latches and bounded timeouts, without sleep. Email/provider remain mocked and no shared database is used.

## Claim Email Outbox + Async

- **MAIL-001 · Unit:** AES-GCM roundtrip, random nonce, tampered payload/wrong key rejection, strict Base64 32-byte key and safe 503; `ClaimEmailCipherTest#authenticatedEncryptionUsesFreshNonceAndPreservesPayload`, `#invalidKeyReturns503WithoutSensitiveDetails`.
- **MAIL-002 · Unit/Spring async:** actual dedicated 2-thread/100-capacity executor, no SMTP transaction, queue rejection recovered by later sweep, safe failure logging; `ClaimEmailAsyncTest#workerRunsOnDedicatedBoundedExecutorAndDoesNotHoldTransaction`, `#rejectedExecutorLeavesPersistedJobsForLaterSweep`, `#smtpFailureLogsOnlyJobIdAndRecordsRetryWithoutSensitiveDetails`.
- **MAIL-003 · PostgreSQL transaction:** listener submits only AFTER_COMMIT, not while creating records; rollback discards job/event. Worker mocked at the dispatch boundary. `ClaimEmailAfterCommitPostgresIntegrationTest#dispatchOccursOnlyAfterCommitAndRollbackDiscardsJobAndEvent`.
- **MAIL-004 · PostgreSQL configuration/API:** missing key returns 503 before invitation creation; `ClaimEmailConfigurationPostgresIntegrationTest#missingEncryptionKeyReturns503BeforeAnyInvitationIsCreated`.
- **MAIL-005 · PostgreSQL worker:** committed queued job recoverable by due query; encrypted recipient/link hash match; SMTP outside transaction; success erases payload, repeat worker does not resend, status omits secrets. `ClaimEmailOutboxPostgresIntegrationTest#queuedJobHasOnlyCiphertextAndSendsWithoutTransactionThenClearsPayload`.
- **MAIL-006 · PostgreSQL retry:** exact 15/30/60/120 backoff, no premature sends, FAILED keeps PENDING invitation, duplicate invite remains blocked; manual retry respects cooldown and preserves token/email/expiry, eventual success erases payload. `ClaimEmailOutboxPostgresIntegrationTest#failureBackoffAndManualRetryPreserveTokenAndExpiry`.
- **MAIL-007 · PostgreSQL concurrency:** two retry requests enqueue once; two workers while one SMTP send is in flight perform one attempt. `ClaimEmailOutboxPostgresIntegrationTest#concurrentWorkersSendOnceAndConcurrentManualRetriesEnqueueOnce`.
- **MAIL-008 · PostgreSQL recovery:** expired lease replaces token, old worker cannot finalize replacement, five crashed reservations exhaust the cycle. `ClaimEmailOutboxPostgresIntegrationTest#expiredLeaseCanBeRecoveredAndOldWorkerCannotCompleteNewLease`, `#fiveCrashedReservationsExhaustCycleWithoutUnboundedRecoveryAttempts`.
- **MAIL-009 · PostgreSQL guards, parameterized:** expired/consumed/replaced invitation, owned channel and expired FAILED job cause cancellation/expiry without SMTP and erase payload. `ClaimEmailOutboxPostgresIntegrationTest#invalidJobsAreCancelledWithoutSmtpAndPayloadIsErased`.
- **MAIL-010 · PostgreSQL constraint/rollback:** outbox insertion failure rolls back invitation; duplicate job for same invitation violates unique constraint. `ClaimEmailOutboxPostgresIntegrationTest#duplicateOutboxIsRejectedAndInsertFailureRollsBackInvitation`.
- **MAIL-011 · PostgreSQL RBAC/API:** anonymous, non-Brand, foreign Brand, missing invitation and disabled permission denied; historical invitation NOT_TRACKED and not retryable. `ClaimEmailOutboxPostgresIntegrationTest#statusAndRetryRequireOwningBrandAndPermissionAndHistoricalRowsAreNotTracked`.
- **MAIL-012 · PostgreSQL rolling rate limit:** five manual retries in preceding hour block the sixth; expired entry frees a slot without resetting total attempts. `ClaimEmailOutboxPostgresIntegrationTest#manualRetryUsesRollingHourAndDoesNotChangeInvitation`.
- **MAIL-013 · PostgreSQL migration:** V15 historical invitation/account survives V16 byte-for-byte, no historical jobs are invented. Fresh schema is migrated/validated by all current PostgreSQL suites. `ClaimEmailOutboxPostgresIntegrationTest#upgradeFromV15PreservesHistoricalInvitationsWithoutInventingEmailJobs`.

Migration fixtures retain all preservation assertions: cleanup tests pin V15 rather than inadvertently executing V16; fresh-schema tests expect 15 migration files through V16 (V11 is absent) and current version 16. SMTP message regression also confirms a resend does not extend invitation expiry.

SMTP/provider stay mocked. PostgreSQL locks, lease persistence, constraints and transaction boundaries are real. Clocks and latches replace sleeps; tests do not send Gmail or use the shared database. Registration and OTP retain synchronous SMTP rollback regression.
