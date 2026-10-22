# Backend Test Cases — Khải

## Kết quả tích hợp be_vy — 22/10/2026

- Full regression sau xử lý conflict: **506 test / 62 suite; 506 đạt, 0 thất bại, 0 lỗi, 0 bỏ qua**.
- Giữ CreatorMaster, lời mời đăng ký qua SMTP và bắt buộc OTP; gỡ Google Login.
- Bổ sung kiểm tra migration tiến lên giữ ownership/lời mời cũ, đăng ký tạo Master rỗng,
  chặn callback tự tạo User hoặc bỏ qua OTP, và giới hạn OAuth mô phỏng ở dev.
- Test thuộc `be_vy` có một số suite đặt tên Integration nhưng sử dụng Mockito; kết quả
  trên gồm cả các suite đó và các suite API/PostgreSQL Testcontainers thật.
- Đã xử lý lỗi tích hợp lazy ownership, phạm vi nâng cấp V14, fixture thiếu ACTIVE và
  danh mục permission đánh dấu editable cho chức năng chưa triển khai; giữ assertion kiểm tra.
- Không chứa YouTube thật/V15 local, Bruno, `.env`, build/log hoặc bản sửa embedding local.
- Không chạy migration database dùng chung hay restart BE. Xem [integration notes](be-vy-integration.md).


## Kết quả thực thi ngày 22/10/2026 — Creator invitations, gỡ Google Login

- Full regression bản cuối: **450 test / 50 suite; 450 đạt, 0 thất bại, 0 lỗi thực thi, 0 bỏ qua**.
- Danh sách hiện tại ánh xạ **176 nhóm test case**; giữ ID cũ, bỏ các dòng chỉ thuộc Google Login đã gỡ.
- Suite Creator registration/PostgreSQL: **52/52 đạt**; unit lời mời: **8/8 đạt**;
  SMTP template: **5/5 đạt**. Các suite Claim, OTP, phân quyền và nghiệp vụ khác vẫn đạt.
- Đã kiểm chứng Brand không nhận role (kể cả null), Creator chỉ đăng ký qua lời mời,
  OTP trước login, Claim không cần Google identity và không tự cấp ownership.
- V14 được kiểm thử nâng cấp từ V13, giữ account/identity/exchange code/ownership/OTP cũ;
  kiểm tra giới hạn gửi, SMTP/audit/database rollback và cạnh tranh đăng ký/đặt mật khẩu.
- Test ban đầu cần cập nhật số quyền mới và gửi JSON nguyên bản cho `role: null` vì ObjectMapper
  của test bỏ trường null khỏi map. Đã sửa fixture và chạy lại toàn bộ thành công.
- Đã gỡ test thuần Google Login, chuyển fixture sang tài khoản đã xác minh email;
  bổ sung test tài khoản Google cũ đặt mật khẩu, không giảm kiểm tra ownership/Claim.
- Build/test dùng POM tạm và output riêng tại `target/invitation-validation` để không kích hoạt
  DevTools của BE đang phục vụ. Database chỉ là PostgreSQL/pgvector Testcontainers; email giả lập.
- Không sửa `.env` thật/Bruno, không gửi email thật, không chạy V14 trên database dùng chung,
  không khởi động lại BE và chưa commit/push. Xem [Creator Registration](creator-registration.md).
- Các kết quả 407/362/357 bên dưới là lịch sử trước lần thay đổi này.

## Kết quả thực thi ngày 22/10/2026 — đăng ký xác minh email OTP

- Full regression bản cuối: **407 test / 50 suite; 407 đạt, 0 thất bại, 0 lỗi thực thi, 0 bỏ qua**.
- Tăng **45 lượt kiểm thử** so với baseline 362; tài liệu ánh xạ **145 nhóm test case**.
- Suite OTP PostgreSQL: **22/22 đạt**; thêm unit service/SMTP và kiểm tra login chưa xác minh.
- Đã kiểm chứng migration V12 → V13, tài khoản cũ, sai/hết hạn/dùng lại mã, gửi lại/rate limit,
  rollback SMTP/database, bộ đếm sai được commit, cạnh tranh verify/Admin và chặn JWT/refresh/Google.
- Test ban đầu phát hiện hai vấn đề fixture: H2 dùng nhầm PostgreSQL dialect và assertion so sánh
  toàn bộ response có metadata riêng cho mỗi request. Đã sửa cấu hình/assertion và chạy lại thành công.
- SMTP sender/clock được giả lập trong test; chưa gửi email thật hoặc dùng thông tin SMTP thật.
  Không sửa `.env`, Bruno hoặc database dùng chung. Chỉ thêm cấu hình mẫu vào `.env.example`.
- Chưa commit/push phần OTP. Xem [Registration Email Verification](email-verification.md).

## Kết quả thực thi ngày 22/10/2026 — gỡ Creator Rate Card

- Clean full regression: **362 test / 47 suite; 362 đạt, 0 thất bại, 0 lỗi thực thi, 0 bỏ qua**.
- Danh sách bên dưới ánh xạ **114 nhóm test case**; một nhóm có thể chạy nhiều bộ tham số.
- Đã gỡ test thuần nghiệp vụ giá, giữ assertion Claim/ownership/ingestion/Compare và phân quyền.
- Thêm 12 lượt integration test cho endpoint cũ 404, response không có `rateCard`, permission
  đã gỡ và nâng cấp V11 → V12 có/không có override cũ. Khôi phục riêng test claim kênh đã có owner.
- Migration test giữ nguyên giá/audit lịch sử, quyền khác và version của role không bị ảnh hưởng.
- Lượt chọn lọc ban đầu gặp lỗi Docker engine chưa chạy (33 lượt: 18 đạt, 15 lỗi khởi tạo,
  không có assertion thất bại). Sau khi Docker hoạt động, đã chạy lại bằng clean full regression
  trên PostgreSQL/pgvector Testcontainers riêng và đạt kết quả trên.
- V9 không đổi; không chạy migration lên database dùng chung. Không sửa `.env`, Bruno hoặc frontend.
- Các kết quả 357 test dưới đây là lịch sử trước khi gỡ chức năng; phần gỡ đã push ở commit `6e6f6fd`.

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

### Authentication & Google OAuth

| ID | Type | Preconditions / Action | Expected result | Automated test |
|---|---|---|---|---|
| BE-001 | Unit | Register padded mixed-case email/name | Save trimmed lowercase email, trimmed name, BRAND and encoded password | [AuthServiceTest](../src/test/java/com/influencermatch/backend/auth/service/AuthServiceTest.java)`#registerTrimsEmailAndNameAndNeverPersistsPlainPassword` |
| BE-002 | Unit | AuthenticationManager rejects password | Reject without tokens or user save | [AuthServiceTest](../src/test/java/com/influencermatch/backend/auth/service/AuthServiceTest.java)`#invalidPasswordDoesNotGenerateAnyTokens` |
| BE-003 | Unit | Rotate token expired at fixed past timestamp | Reject; no new token or revocation of other sessions | [RefreshTokenServiceTest](../src/test/java/com/influencermatch/backend/auth/service/RefreshTokenServiceTest.java)`#expiredTokenDoesNotCreateOrRevokeOtherSessions` |
| BE-004 | Unit | Rotate/revoke unknown token; revoke already revoked token | Reject rotation; revoke is idempotent and preserves timestamp | [RefreshTokenServiceTest](../src/test/java/com/influencermatch/backend/auth/service/RefreshTokenServiceTest.java)`#unknownTokenAndIdempotentRevokeDoNotWrite` |
| BE-005 | Unit | Principal role differs from database role | Reject before refresh-token write | [RefreshTokenServiceTest](../src/test/java/com/influencermatch/backend/auth/service/RefreshTokenServiceTest.java)`#changedRoleCannotIssueTokenFromStalePrincipal` |
| BE-008 | Integration | Register mixed-case Brand email; repeat | BRAND, encoded password, trimmed name; duplicate 409; no Brand Profile | [AuthSessionPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/AuthSessionPostgresIntegrationTest.java)`#registrationNormalizesCaseAndCreatesBrand` |
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
| BE-111 | Integration | Claim, approve, compare and revoke with legacy prices | Verification works; no rateCard JSON; legacy prices untouched | [RetiredRateCardPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/RetiredRateCardPostgresIntegrationTest.java)`#claimRevokeAndCompareWorkWithoutExposingOrModifyingLegacyPrices` |
| BE-112 | Integration | Read permission catalog/auth and submit retired permission | Permission absent; update rejected | [RetiredRateCardPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/RetiredRateCardPostgresIntegrationTest.java)`#retiredPermissionIsAbsentAndCannotBeConfigured` |
| BE-113 | Integration | Upgrade V11 to V12 with/without retired override | Prices/audits retained; override removed; only affected role version increases | [RetiredRateCardPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/RetiredRateCardPostgresIntegrationTest.java)`#upgradePreservesLegacyDataAndOnlyBumpsAffectedConfiguration` |
| BE-114 | Integration | Claim already authorized channel | 409; existing owner retained | [CreatorClaimPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/creator/CreatorClaimPostgresIntegrationTest.java)`#authorizedChannelRejectsNewClaims` |

### Registration Email Verification

Các test trước đây đăng ký rồi đăng nhập đã được cập nhật để xác minh email trước;
fixture tạo trực tiếp User vẫn mặc định đã xác minh như dữ liệu cũ.

| ID | Level | Condition / action | Expected | Automated test |
| --- | --- | --- | --- | --- |
| BE-115 | Unit | Leading-zero code / initial send | Only keyed hash stored; 10-minute expiry | [EmailVerificationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/EmailVerificationServiceTest.java)`#initialDeliveryKeepsLeadingZerosAndOnlyStoresKeyedHash` |
| BE-116 | Unit | Verify with normalized email | Verified and code consumed | [EmailVerificationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/EmailVerificationServiceTest.java)`#verifyNormalizesEmailConsumesCodeAndMarksVerified` |
| BE-117 | Unit | Wrong attempts 1–5 | Counter increments, fifth attempt locks | [EmailVerificationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/EmailVerificationServiceTest.java)`#wrongAttemptIsCountedAndFifthLocksCode` |
| BE-118 | Unit | Expired/consumed/exhausted code | Cannot verify | [EmailVerificationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/EmailVerificationServiceTest.java)`#unusableCodesNeverVerify` |
| BE-119 | Unit | Unknown or verified account | No verification | [EmailVerificationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/EmailVerificationServiceTest.java)`#alreadyVerifiedAndUnknownEmailsCannotBeVerified` |
| BE-120 | Unit | Unknown or verified account resend | No email sent | [EmailVerificationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/EmailVerificationServiceTest.java)`#resendDoesNothingForVerifiedOrUnknownEmail` |
| BE-121 | Unit | Resend after cooldown | Reset attempts; invalidate previous code | [EmailVerificationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/EmailVerificationServiceTest.java)`#resendAfterCooldownResetsAttemptsAndInvalidatesOldCode` |
| BE-122 | Unit | Cooldown/hourly rate limit | No code replacement/send | [EmailVerificationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/EmailVerificationServiceTest.java)`#resendLimitsNeverReplaceCodeOrSend` |
| BE-123 | Unit | Random collision with previous code | Regenerate different code | [EmailVerificationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/EmailVerificationServiceTest.java)`#resendRegeneratesIfRandomCodeMatchesPreviousCode` |
| BE-124 | Unit | SMTP unavailable | Fail before resend lookup | [EmailVerificationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/EmailVerificationServiceTest.java)`#unavailableSenderFailsBeforeAnyLookup` |
| BE-125 | Unit | OTP crypto and format | Six digits, account/secret-bound hash | [EmailVerificationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/EmailVerificationServiceTest.java)`#keyedHashIsBoundToAccountAndSecretAndGeneratorHasSixDigits` |
| BE-126 | Unit | SMTP disabled/incomplete | 503, no sending | [SmtpVerificationEmailSenderTest](../src/test/java/com/influencermatch/backend/auth/service/SmtpVerificationEmailSenderTest.java)`#disabledOrIncompleteConfigurationCannotSend` |
| BE-127 | Unit | SMTP message delivery | Correct recipient/code/expiry | [SmtpVerificationEmailSenderTest](../src/test/java/com/influencermatch/backend/auth/service/SmtpVerificationEmailSenderTest.java)`#sendsOtpToRecipientAndIncludesExpiration` |
| BE-128 | Unit | SMTP provider exception | 503 without server details | [SmtpVerificationEmailSenderTest](../src/test/java/com/influencermatch/backend/auth/service/SmtpVerificationEmailSenderTest.java)`#smtpErrorsAreMappedWithoutLeakingServerDetails` |
| BE-129 | Unit | Unverified login | No token or last-login update | [AuthServiceTest](../src/test/java/com/influencermatch/backend/auth/service/AuthServiceTest.java)`#unverifiedLoginNeverUpdatesLastLoginOrIssuesTokens` |
| BE-130 | Integration | Registration → verify → login | No tokens until verified; duplicate/reuse rejected | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#registrationMustBeVerifiedBeforeLoginAndTokensAreNeverLeaked` |
| BE-131 | Integration | Wrong verification attempts | Counter persists despite error and locks at five | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#failedAttemptsCommitAndFifthAttemptLocksEvenTheCorrectCode` |
| BE-132 | Integration | Expiry exactly ten minutes | Reject; resend can restore verification | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#codeExpiresAtTenMinutesAndCanBeReplacedWithoutSleep` |
| BE-133 | Integration | Resend at 59/60 seconds | Cooldown enforced; old code invalid | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#resendCooldownAndReplacementInvalidateOldCode` |
| BE-134 | Integration | Rolling-hour send budget | Initial email counted; budget recovers | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#fiveEmailsPerRollingHourIncludesRegistration` |
| BE-135 | Integration | Resend verified/unknown email | Same public response semantics, no sending | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#resendOfUnknownOrVerifiedEmailUsesSameEnvelopeAndDoesNotSend` |
| BE-136 | Integration | Registration SMTP/config failure | 503 and account/code rollback | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#registrationFailureRollsBackAccountAndCode` |
| BE-137 | Integration | Resend SMTP failure | Old code/counters/delivery history retained | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#smtpFailureDuringResendRollsBackHashCountersAndDelivery` |
| BE-138 | Integration | Malformed OTP body | 400 without consuming attempts | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#invalidOtpPayloadNeverChangesAttemptCount` |
| BE-139 | Integration | Missing account or OTP state | 400, account not verified | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#unknownEmailAndMissingOtpStateCannotBeVerified` |
| BE-140 | Integration | Concurrent verify | Exactly one success | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#concurrentVerificationConsumesCodeOnlyOnce` |
| BE-141 | Integration | Pending account / Admin role/status update | Cannot bypass email verification | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#pendingAccountCannotBeActivatedByAdminStatusOrRole` |
| BE-142 | Integration | Existing account and refresh | Existing verified; token/refresh cannot bypass flag | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#existingAccountsRemainVerifiedButRefreshCannotBypassVerification` |
| BE-143 | Integration | Verification database update failure | Flag and code consumption both roll back | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#verificationDatabaseFailureRollsBackConsumptionAndAccountFlag` |
| BE-144 | Integration | V12 → V13 migration | Existing accounts verified; new tables created | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#v13UpgradeGrandfathersExistingAccountsAndAddsVerificationStorage` |
| BE-145 | Integration | Verify concurrently with Admin role change | Both updates retained; verified flag remains true | [EmailVerificationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/EmailVerificationPostgresIntegrationTest.java)`#concurrentAdminRoleChangeCannotOverwriteSuccessfulVerification` |

### Creator Invitations and Legacy Password Setup

| ID | Level | Condition / action | Expected | Automated test |
| --- | --- | --- | --- | --- |
| BE-146 | Integration | Invited Creator registers, verifies OTP, logs in and claims | No Google identity; no ownership until manual approval | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#creatorInvitationRegistrationOtpLoginAndClaimCompleteWithoutGoogle` |
| BE-147 | Integration | Admin/Data Manager sends invitation | 201 metadata only; link only in email | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#adminsAndDataManagersCanInviteWithoutTokensInResponse` |
| BE-148 | Integration | Brand/Creator sends invitation | 403; no mail | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#otherRolesCannotInvite` |
| BE-149 | Integration | Anonymous or same token after permission disabled | 401/403; enabling again works | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#anonymousAndDynamicallyDisabledInviterAreDenied` |
| BE-150 | Integration | Public registration includes any role, including null | 400; no account created | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#publicRegistrationRejectsAnyRoleProperty` |
| BE-151 | Integration | Public registration without role | BRAND unverified, OTP then login | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#publicRegistrationAlwaysCreatesUnverifiedBrand` |
| BE-152 | Integration | Creator request overrides email/role | 400; invitation remains usable | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#creatorRegistrationRejectsClientEmailOrRole` |
| BE-153 | Integration | Missing token/password, short password or blank name | 400; no account or token consumption | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#invalidCreatorRegistrationNeverConsumesInvitation` |
| BE-154 | Integration | Invalid recipient email | 400; no invitation mail | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#invalidInvitationEmailNeverSends` |
| BE-155 | Integration | Invalid/expired/replaced invitation and cooldown boundary | 400 for old token; 429 before 60 seconds | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#invalidExpiredAndReplacedInvitationCannotRegister` |
| BE-156 | Integration | Five invitations per rolling hour | Registration-independent send budget recovers after hour | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#invitationSendBudgetIsFivePerRollingHour` |
| BE-157 | Integration | Invited email becomes existing Brand | 409; no role conversion | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#existingBrandCannotBeInvitedOrConverted` |
| BE-158 | Integration | Initial invitation SMTP failure | Invitation, delivery and audit rolled back | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#invitationSmtpFailureRollsBackTokenDeliveryAndAudit` |
| BE-159 | Integration | Replacement invitation SMTP failure | Previous link and delivery count retained | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#failedInvitationReplacementPreservesPreviousLinkAndBudget` |
| BE-160 | Integration | Invitation audit database failure | Invitation and delivery rolled back | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#auditFailureRollsBackInvitationAndDelivery` |
| BE-161 | Integration | Creator registration OTP SMTP failure | No account; invitation remains valid | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#registrationSmtpFailureLeavesInvitationUsableAndNoAccount` |
| BE-162 | Integration | Two registrations use same invitation | One 201 and one 400; one account and OTP | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#concurrentRegistrationConsumesInvitationOnce` |
| BE-163 | Integration | Authenticated calls to removed OAuth routes | 404; no Google login | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#removedGoogleEndpointsAre404ForAuthenticatedUsers` |
| BE-164 | Integration | Legacy BRAND/CREATOR sets first password | Role/ID preserved; version increased; old sessions revoked | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#legacyGoogleAccountSetsPasswordAndRevokesOldSessions` |
| BE-165 | Integration | Unknown/inactive/privileged/already-password/no-identity account | Generic request success, no mail; confirm rejected | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#ineligiblePasswordSetupIsGenericAndDoesNotSend` |
| BE-166 | Integration | Wrong password-setup OTP five times then resend | Attempts persist, code locks; replacement resets | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#passwordWrongAttemptsPersistAndResendResetsWithNewPurposeBoundCode` |
| BE-167 | Integration | Code purpose and exact expiration | Setup code cannot verify registration; expires at ten minutes | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#passwordCodeCannotBeUsedForRegistrationAndExpiresAtTenMinutes` |
| BE-168 | Integration | Shared account OTP send budget | Registration delivery history limits password setup | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#passwordSetupSharesHourlyDeliveryBudget` |
| BE-169 | Integration | Password setup resend SMTP failure | Old code and budget preserved | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#passwordSmtpFailurePreservesOldCodeAndBudget` |
| BE-170 | Integration | Password update database failure | Password/version/code/session revocation rolled back | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#passwordDatabaseFailureRollsBackPasswordVersionCodeAndRevocation` |
| BE-171 | Integration | Two password confirmations | One success; one invalid confirmation | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#concurrentPasswordConfirmationOnlySucceedsOnce` |
| BE-172 | Integration | V13 to V14 migration | Accounts/identity/exchange/ownership/old OTP preserved | [CreatorRegistrationPostgresIntegrationTest](../src/test/java/com/influencermatch/backend/auth/CreatorRegistrationPostgresIntegrationTest.java)`#v14UpgradePreservesAccountsIdentitiesOwnershipAndExistingOtp` |
| BE-173 | Unit | Normalize invitation and generate link | Account-independent 256-bit token, hash stored, metadata-only audit | [CreatorInvitationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/CreatorInvitationServiceTest.java)`#invitationNormalizesEmailStoresHashAndAuditsOnlyMetadata` |
| BE-174 | Unit | Invite existing account | No role conversion or mail | [CreatorInvitationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/CreatorInvitationServiceTest.java)`#existingAccountIsNeverConvertedOrEmailed` |
| BE-175 | Unit | Invitation cooldown or hourly budget exceeded | No token replacement | [CreatorInvitationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/CreatorInvitationServiceTest.java)`#rateLimitNeverReplacesToken` |
| BE-176 | Unit | Expired/consumed/missing invitation | No account created | [CreatorInvitationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/CreatorInvitationServiceTest.java)`#invalidInvitationNeverCreatesAccount` |
| BE-177 | Unit | Register with valid invitation | Bound email, CREATOR, unverified flag and OTP | [CreatorInvitationServiceTest](../src/test/java/com/influencermatch/backend/auth/service/CreatorInvitationServiceTest.java)`#registrationUsesInvitedEmailAndCreatorRoleAndRequiresOtp` |
| BE-178 | Unit | Invitation email template | Configured link, 48-hour expiry | [SmtpVerificationEmailSenderTest](../src/test/java/com/influencermatch/backend/auth/service/SmtpVerificationEmailSenderTest.java)`#invitationMessageContainsConfiguredLinkAndFortyEightHourLimit` |
| BE-179 | Unit | Password setup email template | Purpose-specific subject and ten-minute expiry | [SmtpVerificationEmailSenderTest](../src/test/java/com/influencermatch/backend/auth/service/SmtpVerificationEmailSenderTest.java)`#passwordSetupMessageHasItsOwnPurpose` |
