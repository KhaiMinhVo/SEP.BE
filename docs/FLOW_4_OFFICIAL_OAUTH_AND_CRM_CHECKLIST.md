# 🤝 FLOW 4: Shortlist, Contact, Official OAuth Sandbox & Basic CRM
> **Phân hệ**: Định danh KOC Con người Thật (Creator Master M1), Đối soát Kênh Đa nền tảng & Quản trị Phễu Đàm phán Hợp tác.  
> **Dự án**: InfluencerMatch AI (Capstone Project - SEP490)  
> **Phạm vi mã nguồn**: `D:\SEP_BE` (Core Backend) & `D:\creator-discovery` (Data Safety Verification).  
> **Mục tiêu**: Tận dụng môi trường thử nghiệm chính thức (**Official Developer Sandbox / Testing Mode**) của TikTok và Google thay vì tự viết hệ thống giả lập OAuth Provider nội bộ, đảm bảo tính chân thực và chuyên nghiệp cao nhất khi bảo vệ trước hội đồng.

---

## 📌 I. NGUYÊN TẮC KIẾN TRÚC & PHÂN TÁCH 2 TIỂU LUỒNG ĐỘC LẬP

Hệ thống Flow 4 được tách biệt rành mạch thành 2 chu trình nghiệp vụ độc lập nhưng kết nối chặt chẽ qua dữ liệu:

```
                                    HỆ THỐNG FLOW 4
                                           │
         ┌─────────────────────────────────┴─────────────────────────────────┐
         ▼                                                                   ▼
   【FLOW A: CREATOR IDENTITY & CLAIM】                         【FLOW B: BRAND COLLABORATION CRM】
  (Định danh KOC & Kết nối Kênh Mạng xã hội)                   (Quản trị Phễu Hợp tác Chiến dịch)
  1. Nhận Claim Email có Token bảo mật                         1. Brand xem Shortlist Cart (Flow 3)
  2. Xem hồ sơ kênh công khai (Pre-claim)                      2. So sánh KOC Side-by-Side (Compare)
  3. Chuyển hướng Official OAuth Sandbox                       3. Kiểm tra Contact Readiness
  4. Đăng nhập & Ủy quyền trên nền tảng thật                   4. Gửi Lời mời Hợp tác (Outreach)
  5. SEP_BE đổi Code, lấy Profile thật                         5. Quản lý Phễu CRM (State Machine)
  6. Match danh tính & Tạo nguyên tử Master M1                 6. Thương lượng chi phí (Agreed Fee)
  7. Multi-channel Linking & Aggregated Reach
```

### 1. Phân cấp 3 chế độ OAuth Proof of Control
* **P0 – TikTok Developer Sandbox (Login Kit for Web)**: Môi trường chính thức của TikTok dành cho Developer, hỗ trợ tối đa 10 tài khoản test. Người dùng thực sự trải nghiệm màn hình đăng nhập và cấp quyền của TikTok.
* **P1 – Google OAuth Testing Mode (YouTube Data API v3)**: Chế độ Testing chính thức của Google, hỗ trợ 100 tài khoản test. Gọi API `channels.list(mine=true)` để lấy Channel ID (`UC...`) đối soát.
* **P2 – Isolated Simulated Fallback (Chế độ dự phòng cô lập)**: Chỉ kích hoạt khi chạy Unit Test trên CI/CD hoặc demo offline trong điều kiện mất kết nối Internet quốc tế, được đóng gói gọn trong `OAuthProofClient` và đánh dấu `SIMULATED`.

### 2. Hai "Bẫy kỹ thuật" đối soát danh tính (Identity Matching)
1. **TikTok `open_id` trap**: `open_id` của TikTok là định danh riêng theo từng App Developer, **không trùng** với `externalId` công khai do crawler thu thập.  
   $\rightarrow$ **Giải pháp**: Gọi TikTok User Info API (`/v2/user/info/`), lấy `username`, chuẩn hóa bỏ `@` và kiểm tra `expectedUserName.equalsIgnoreCase(proofUserName)`.
2. **YouTube Channel ID trap**: Đăng nhập Google chỉ trả về Gmail và `sub` (Google User ID), **không phải Channel ID**.  
   $\rightarrow$ **Giải pháp**: Dùng Access Token gọi `https://www.googleapis.com/youtube/v3/channels?part=id,snippet&mine=true` để lấy `channelId` và so khớp với `creator.externalId`.

---

## 📊 II. MA TRẬN FUNCTION & TRẠNG THÁI TRIỂN KHAI THEO SRC CODE HIỆN TẠI

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Hiện trạng | Hành động cần hoàn thiện |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **4.1** | `POST /campaigns/{id}/shortlist/compare`<br>([ShortlistCompareController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/controller/ShortlistCompareController.java)) | Brand User | `SEP_BE` | **In**: `List<creatorId>`<br>**Out**: `CompareResponse` | 🟢 **ĐÃ XONG** | So sánh 2–5 KOC cạnh nhau theo metrics mới nhất (Followers, ER, Avg Views) và trạng thái xác minh. |
| **4.2** | `POST /creators/claim/invitation`<br>([CreatorInvitationService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorInvitationService.java)) | Brand User | `SEP_BE` | **In**: `creatorId, email`<br>**Out**: `InvitationResponse` | 🟢 **ĐÃ XONG** | Tạo lời mời Claim, sinh token 64 ký tự, băm SHA-256 lưu DB, đặt TTL 7 ngày. |
| **4.3** | `GET /creators/claim/invitation/verify`<br>([CreatorInvitationService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorInvitationService.java)) | KOC User | `SEP_BE` | **In**: `?token=...`<br>**Out**: `VerifyResponse` | 🟢 **ĐÃ XONG** | Xem trước hồ sơ kênh công khai trước khi đăng nhập để claim. |
| **4.4** | `GET /creators/claim/oauth/{platform}/start`<br>([CreatorClaimOAuthController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorClaimOAuthController.java)) | KOC User | `SEP_BE` | **In**: `platform, token`<br>**Out**: 302 Redirect | 🟡 **CẦN SỬA** | Thay vì redirect ngay `code=sim-auth-code`, cần sinh URL OAuth chuyển hướng sang **Official Sandbox** của TikTok/Google kèm signed `state`. |
| **4.5** | `GET /creators/claim/oauth/{platform}/callback`<br>([CreatorClaimOAuthController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorClaimOAuthController.java)) | Nền tảng OAuth | `SEP_BE` | **In**: `code, state`<br>**Out**: `OAuthCallbackResponse` | 🟡 **CẦN SỬA** | Tiếp nhận Authorization Code thật từ Sandbox/Testing, đổi Token và đối soát danh tính. |
| **4.6** | `resolveClaim()`<br>([CreatorClaimResolutionService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorClaimResolutionService.java)) | KOC User | `SEP_BE` | **In**: `platform, code, token`<br>**Out**: `OAuthCallbackResponse` | 🟢 **ĐÃ XONG 90%** | Đã có: Lock bi quan, tạo Master M1 nguyên tử, chuyển `sourceType = AUTHORIZED`, mã hóa Token AES-256-GCM, cập nhật Shortlist/Relationship. Cần: gắn tag `SANDBOX_VERIFIED`. |
| **4.7** | `GET /creators/portal/accounts`<br>([CreatorPortalAccountController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorPortalAccountController.java)) | KOC User | `SEP_BE` | **In**: Bearer KOC<br>**Out**: `List<LinkedAccountDto>` | 🟢 **ĐÃ XONG** | Trả về danh sách tất cả các kênh (TikTok, YouTube, Facebook) thuộc Master M1 của KOC. |
| **4.8** | `POST /creators/portal/accounts/connect/{platform}/start` & `callback`<br>([CreatorAccountLinkService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorAccountLinkService.java)) | KOC User | `SEP_BE` | **In**: `code, platform`<br>**Out**: `ConnectAccountResponse` | 🟢 **ĐÃ XONG** | Xử lý 4 kịch bản liên kết kênh đa nền tảng (Kênh có sẵn / Kênh mới toanh / Refresh Token / Tranh chấp). |
| **4.9** | `GET /creators/portal/analytics/reach`<br>(Creator Analytics) | KOC User | `SEP_BE` | **In**: Bearer KOC<br>**Out**: `AggregatedReachDto` | 🔴 **CHƯA LÀM** | Tính tổng độ phủ (Cross-platform Aggregated Reach): Tổng followers, tổng views, ER trung bình của toàn bộ các kênh thuộc Master M1. |
| **4.10** | `POST /admin/creator-conflicts/{id}/resolve`<br>([AdminIdentityConflictController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/AdminIdentityConflictController.java)) | Data Manager | `SEP_BE` | **In**: `decision, notes`<br>**Out**: `ConflictDetailDto` | 🔴 **CHƯA LÀM** | Expose endpoint giải quyết tranh chấp (Service đã viết và test xong 100%, chỉ thiếu Controller method). |
| **4.11** | `POST /campaigns/{id}/shortlist/outreach`<br>(Shortlist Outreach) | Brand User | `SEP_BE` | **In**: `itemIds, message, fee`<br>**Out**: `OutreachResultDto` | 🔴 **CHƯA LÀM** | Chuyển hàng loạt KOC từ Shortlist sang trạng thái `CONTACTED` trong CRM, phân luồng gửi email booking tự động cho kênh chưa claim. |
| **4.12** | `PATCH /campaigns/{id}/relationships/{relId}/stage`<br>([RelationshipController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/controller/RelationshipController.java)) | Brand User | `SEP_BE` | **In**: `stage, agreedFee`<br>**Out**: `RelationshipDto` | 🟢 **ĐÃ XONG** | Chuyển đổi trạng thái phễu CRM (`CONTACTED` $\to$ `INTERESTED` $\to$ `COLLABORATING`) và chốt `agreedFee`. |
| **4.13** | Bảo vệ dữ liệu Claim trong Crawler<br>([java_mapper.py](file:///d:/creator-discovery/app/mappers/java_mapper.py)) | AI Engine | `AI` | **In**: Crawled Profile<br>**Out**: Ingest JSON | 🟢 **ĐÃ XONG** | `JavaMapper` đóng cứng `sourceType = 'PUBLIC'` và `masterId = None`. Crawler vĩnh viễn không ghi đè quyền sở hữu kênh đã claim. |

---

## 🛠️ III. CHI TIẾT 6 PHASE HOÀN THIỆN FLOW 4

---

### 🔹 PHASE 1: Official OAuth Sandbox & Testing Client Architecture (`SEP_BE`)
**Mục tiêu**: Xây dựng kiến trúc OAuth Provider Client chuẩn mực, kết nối với TikTok Developer Sandbox và Google OAuth Testing Mode thay vì sinh mã giả lập.

#### 1. Cấu hình hệ thống ([`application.yml`](file:///d:/SEP_BE/src/main/resources/application.yml))
Bổ sung các tham số OAuth linh hoạt theo môi trường:
```yaml
application:
  oauth:
    mode: ${OAUTH_VERIFICATION_MODE:MOCK_FALLBACK} # MOCK_FALLBACK | OFFICIAL_SANDBOX
    tiktok:
      client-key: ${TIKTOK_SANDBOX_CLIENT_KEY:}
      client-secret: ${TIKTOK_SANDBOX_CLIENT_SECRET:}
      redirect-uri: ${TIKTOK_SANDBOX_REDIRECT_URI:http://localhost:8080/api/v1/creators/claim/oauth/tiktok/callback}
      auth-url: https://www.tiktok.com/v2/auth/authorize/
      token-url: https://open.tiktokapis.com/v2/oauth/token/
      user-info-url: https://open.tiktokapis.com/v2/user/info/
    google:
      client-id: ${GOOGLE_TESTING_CLIENT_ID:}
      client-secret: ${GOOGLE_TESTING_CLIENT_SECRET:}
      redirect-uri: ${GOOGLE_TESTING_REDIRECT_URI:http://localhost:8080/api/v1/creators/claim/oauth/youtube/callback}
      auth-url: https://accounts.google.com/o/oauth2/v2/auth
      token-url: https://oauth2.googleapis.com/token
      youtube-channel-url: https://www.googleapis.com/youtube/v3/channels
```

#### 2. Tái cấu trúc Client: `OAuthProofClient` thành Provider Strategy
Tạo interface `OAuthProofProvider` và 3 implementation cụ thể:
1. **`TikTokSandboxProofProvider`**:
   * `buildAuthorizationUrl(state)`: `scope=user.info.basic,video.list`.
   * `exchangeCode(code)`: `POST https://open.tiktokapis.com/v2/oauth/token/` $\to$ nhận `access_token`, `open_id`.
   * `fetchProfile(accessToken)`: `GET https://open.tiktokapis.com/v2/user/info/?fields=open_id,union_id,avatar_url,display_name,username`.
   * Bóc tách `username` để khớp với `creator.userName`.
2. **`GoogleYouTubeTestingProofProvider`**:
   * `buildAuthorizationUrl(state)`: `scope=https://www.googleapis.com/auth/youtube.readonly`.
   * `exchangeCode(code)`: `POST https://oauth2.googleapis.com/token`.
   * `fetchProfile(accessToken)`: `GET https://www.googleapis.com/youtube/v3/channels?part=id,snippet&mine=true`.
   * Bóc tách `items[0].id` (YouTube Channel ID `UC...`) để khớp với `creator.externalId`.
3. **`SimulatedFallbackProofProvider`**:
   * Hoạt động khi `application.oauth.mode == MOCK_FALLBACK` hoặc credentials để trống.
   * Hỗ trợ mock token có tiền tố `sim:` và `sim-mismatch:` phục vụ unit test tự động mà không cần mạng Internet.

---

### 🔹 PHASE 2: Official OAuth Flow & Proof-of-Control Resolution (`SEP_BE`)
**Mục tiêu**: Nối luồng start OAuth sang Provider thật, xử lý Callback, kiểm tra Proof of Control và kích hoạt CreatorMaster M1.

#### 1. Cập nhật `CreatorClaimOAuthController.java`
* **`startOAuth`**:
  * Kiểm tra hợp lệ `token` qua `invitationService.verifyInvitationToken(token)`.
  * Lấy Provider tương ứng (`TIKTOK` hoặc `YOUTUBE`).
  * Trả về HTTP 302 Redirect chuyển hướng trình duyệt thẳng tới trang cấp quyền của **TikTok Developer Sandbox** hoặc **Google Testing Consent Screen**.
  * `state` truyền đi chứa chuỗi mã hóa an toàn gồm `tokenHash` và `nonce` chống giả mạo CSRF.
* **`handleOAuthCallback` (GET & POST)**:
  * Nhận `code` và `state` từ nền tảng gửi về.
  * Gọi `claimResolutionService.resolveClaim(platform, code, state, actor)`.

#### 2. Tinh chỉnh `CreatorClaimResolutionService.java`
* Thực hiện đối soát danh tính:
  * **TikTok**: So khớp `@expected_username` với `proof.providerUsername` (chuẩn hóa loại bỏ khoảng trắng và `@`).
  * **YouTube**: So khớp `creator.externalId` (Channel ID `UC...`) với `proof.providerUserId`.
* Xử lý kết quả:
  * Nếu không khớp: Quăng `ConflictException(ErrorCode.IDENTITY_MISMATCH)`, ghi nhận `CreatorIdentityAudit` với hành động `PROOF_MISMATCH_REJECTED`.
  * Nếu khớp:
    * Tạo hoặc lấy `CreatorMaster` M1 cho User hiện tại.
    * Ghi nhận `verificationMethod = "OAUTH_OFFICIAL_SANDBOX"` (hoặc `"OAUTH_TESTING_GOOGLE"`).
    * Đánh dấu kênh `creator.sourceType = AUTHORIZED`, `creator.master = master`.
    * Mã hóa Token bằng AES-256-GCM lưu vào `creator_account_verification`.
    * Lưu profile chi tiết vào `creator_provider_identity` (`raw_profile_data`).
    * Đồng bộ tức thì `master_id` sang bảng `shortlist_item` và `relationship` của Brand.

---

### 🔹 PHASE 3: Admin Identity Conflict Resolution Endpoint (`SEP_BE`)
**Mục tiêu**: Mở endpoint còn thiếu để Data Manager giải quyết các tranh chấp sở hữu kênh (Channel Dispute).

#### 1. Cập nhật `AdminIdentityConflictController.java`
Bổ sung endpoint phê duyệt:
```java
@PostMapping("/{id}/resolve")
@PreAuthorize("hasAuthority('RESOLVE_CREATOR_DUPLICATE')")
@Operation(summary = "Resolve creator identity conflict (Approve transfer or Reject)")
public ApiResponse<ConflictDetailDto> resolveConflict(
    @PathVariable("id") UUID conflictId,
    @Valid @RequestBody ResolveConflictRequest request) {

  User actor = Permissions.actor();
  ConflictDetailDto result = conflictService.resolveConflict(
      conflictId, request.getDecision(), request.getNotes(), actor);
  return ApiResponse.ok(result);
}
```
* Khi `APPROVE_TRANSFER`: Kênh được chuyển an toàn sang Master M1 mới, cập nhật toàn bộ Shortlist/Relationship liên quan, ghi Audit log.
* Khi `REJECT`: Giữ nguyên quyền sở hữu cũ, chuyển status tranh chấp thành `REJECTED`.

---

### 🔹 PHASE 4: Shortlist Cart to CRM Outreach Bridging (`SEP_BE`)
**Mục tiêu**: Nối kết quả Shortlist Cart (từ Flow 3) vào phễu đàm phán hợp tác CRM (Flow 4 Tiểu luồng B).

#### 1. Tạo DTOs & Service Method Outreach
Tạo `POST /campaigns/{campaignId}/shortlist/outreach` tại [ShortlistController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/controller/ShortlistController.java):
* **Input**:
  ```json
  {
    "shortlistItemIds": ["UUID1", "UUID2"],
    "outreachMessage": "Chào bạn, Brand muốn mời bạn tham gia chiến dịch ra mắt sản phẩm...",
    "proposedFee": 15000000,
    "currency": "VND",
    "contactMethod": "EMAIL_INVITATION"
  }
  ```
* **Logic xử lý tại `ShortlistService`**:
  1. Thẩm định quyền sở hữu Campaign của Brand.
  2. Duyệt qua từng `ShortlistItem`:
     * Chuyển trạng thái `shortlistItem.status = CONTACTED`.
     * Tạo hoặc cập nhật bản ghi `BrandCreatorRelationship` ở giai đoạn `RelationshipStage.CONTACTED` kèm `quotedFee = proposedFee` và `notes = outreachMessage`.
     * **Đối với Creator chưa Claim (`PUBLIC`)**: Tự động sinh `CreatorInvitation` và đưa vào hàng đợi `claim_email_outbox` (kế thừa V16) để gửi email mời Claim kèm lời ngỏ hợp tác.
     * **Đối với Creator đã Claim (`AUTHORIZED`)**: Tạo thông báo nội bộ trong hệ thống gửi trực tiếp vào Creator Portal.

#### 2. Hoàn thiện State Machine tại `RelationshipServiceImpl.java`
* Đảm bảo luồng đàm phán chuyển trạng thái mượt mà:
  `CONTACTED` $\rightarrow$ `INTERESTED` $\rightarrow$ `COLLABORATING` $\rightarrow$ `COMPLETED`.
* Cập nhật chi phí chốt cuối cùng (`agreedFee`) và hạn chót phản hồi (`nextAction`).

---

### 🔹 PHASE 5: Creator Portal Analytics & Multi-Channel Reach (`SEP_BE`)
**Mục tiêu**: Cung cấp góc nhìn toàn diện cho Creator sau khi Claim thành công để quản lý các kênh đã liên kết.

#### 1. Thêm Endpoint Aggregated Reach
Tại [CreatorPortalAccountController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorPortalAccountController.java):
* **Endpoint**: `GET /creators/portal/analytics/reach`
* **Xử lý**:
  * Tìm `CreatorMaster` của KOC hiện tại.
  * Lấy danh sách toàn bộ các kênh thuộc Master (`creatorRepository.findByMasterId(master.getId())`).
  * Lấy metrics mới nhất của từng kênh từ `public_creator_metric`.
  * **Tính toán Aggregated Metrics**:
    * `totalFollowers`: Tổng số followers cộng dồn từ TikTok + YouTube + Facebook.
    * `totalViews`: Tổng lượt xem trung bình trên các nền tảng.
    * `averageEngagementRate`: Tỷ lệ tương tác bình quân gia quyền.
    * `channelCount`: Số lượng kênh mạng xã hội đã xác minh.
    * `platformBreakdown`: Chi tiết từng kênh kèm trạng thái token (ACTIVE/EXPIRED).

---

### 🔹 PHASE 6: Data Safety Contract & Automated Acceptance Test Suite
**Mục tiêu**: Đảm bảo Crawler không bao giờ phá vỡ trạng thái đã Claim và kiểm thử tự động 100% luồng Flow 4.

#### 1. Kiểm tra an toàn tại `creator-discovery`
* **Kiểm tra [java_mapper.py](file:///d:/creator-discovery/app/mappers/java_mapper.py)**:
  * Khẳng định: `sourceType` luôn phát sinh là `'PUBLIC'`, `masterId` luôn là `None`.
* **Kiểm tra Gatekeeper tại `CreatorIngestionService.java` (`SEP_BE`)**:
  * Khi Crawler đẩy lại hồ sơ của một kênh đã Claim, câu lệnh SQL Upsert **chỉ cập nhật metrics (`public_creator_metric`) và bài đăng (`post`)**, tuyệt đối **không ghi đè `source_type` (vẫn giữ nguyên `AUTHORIZED`)** và **không xóa `master_id`**.

#### 2. Bộ kiểm thử tự động toàn diện: `Flow4ComprehensiveAcceptanceTest.java`
Xây dựng 10 kịch bản kiểm thử mẫu mực (`TEST-F4-01` $\rightarrow$ `TEST-F4-10`):
* `TEST-F4-01 [Redirect]`: `startOAuth` chuyển hướng chính xác đến Authorization Endpoint của TikTok/Google với đầy đủ `client_id`, `redirect_uri` và `state`.
* `TEST-F4-02 [TikTok Claim]`: Callback trả về TikTok code $\to$ đối soát đúng username $\to$ tạo `CreatorMaster` M1 nguyên tử, set `AUTHORIZED`.
* `TEST-F4-03 [YouTube Claim]`: Callback trả về Google code $\to$ gọi YouTube API lấy Channel ID $\to$ đối soát đúng `externalId` $\to$ gắn kênh vào Master.
* `TEST-F4-04 [Identity Mismatch]`: Tài khoản đăng nhập trên Sandbox không khớp với KOC được mời $\to$ Báo lỗi `409 IDENTITY_MISMATCH`, không tạo Master, ghi Audit log.
* `TEST-F4-05 [Anti-Replay]`: Dùng lại Authorization Code hoặc Token mời đã được chấp nhận $\to$ Báo `409 INVITATION_ALREADY_PROCESSED`.
* `TEST-F4-06 [Multi-Channel Link]`: KOC đã có Master claim thêm kênh thứ 2 $\to$ Channel 2 chuyển sang `AUTHORIZED` dưới cùng một `master_id`.
* `TEST-F4-07 [Conflict Queue]`: KOC claim kênh đã thuộc sở hữu của người khác $\to$ Tạo bản ghi `CreatorIdentityConflict` ở `PENDING_MANAGER`.
* `TEST-F4-08 [Admin Resolve Conflict]`: Data Manager gọi `POST /admin/creator-conflicts/{id}/resolve` $\to$ Chuyển kênh thành công hoặc từ chối đúng logic.
* `TEST-F4-09 [Shortlist Outreach]`: Brand chọn 3 KOC từ Shortlist bấm gửi Lời mời $\to$ Tự động tạo quan hệ CRM `CONTACTED` và đưa email vào outbox.
* `TEST-F4-10 [CRM State Machine]`: Đàm phán thành công $\to$ Cập nhật `agreedFee` $\to$ Chuyển sang `COLLABORATING`.

---

## 🎯 IV. LỢI ĐIỂM KHI BẢO VỆ CAPSTONE TRƯỚC HỘI ĐỒNG

1. **Tính chân thực (Authenticity)**: Khi demo, nhóm có thể mở trực tiếp cửa sổ đăng nhập chính thức của TikTok Login Kit Sandbox hoặc Google Testing Consent Screen. Hội đồng sẽ thấy đây là tích hợp thực tế với hệ sinh thái Big Tech, không phải một màn hình đăng nhập giả tự vẽ.
2. **Tiết kiệm khối lượng code dư thừa**: Không cần tốn công tạo bảng quản lý session, không cần tự code form HTML đăng nhập giả, không cần tự sinh mã `auth_code`. Toàn bộ thời gian được tập trung cho giá trị cốt lõi: **đối soát danh tính, bảo mật mã hóa token và xử lý tranh chấp đa kênh**.
3. **Giải quyết triệt để bài toán KOC Đa Nền Tảng**: Minh chứng được việc 1 người thật (Master M1) có thể sở hữu đồng thời 1 kênh TikTok và 1 kênh YouTube, với số liệu độ phủ được cộng dồn chuẩn xác (`Aggregated Reach`).

---

## ✅ V. IMPLEMENTATION TRACKING CHECKLIST

### Phase 1: OAuth Sandbox & Testing Client Architecture
- [x] Bổ sung cấu hình `application.oauth.tiktok.*` và `application.oauth.google.*` vào [application.yml](file:///d:/SEP_BE/src/main/resources/application.yml).
- [x] Tạo interface `OAuthProofProvider` định nghĩa `buildAuthorizationUrl()` và `exchangeAndVerify()`.
- [x] Triển khai `TikTokSandboxProofProvider` tích hợp TikTok Login Kit for Web Sandbox.
- [x] Triển khai `GoogleYouTubeTestingProofProvider` tích hợp Google OAuth 2.0 & YouTube Data API v3.
- [x] Refactor `OAuthProofClient` thành factory/strategy điều hướng theo `application.oauth.mode`.

### Phase 2: Official OAuth Flow & Proof-of-Control Resolution
- [x] Cập nhật `startOAuth` trong [CreatorClaimOAuthController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorClaimOAuthController.java) trả về HTTP 302 Redirect chuyển hướng nền tảng thật.
- [x] Cập nhật `handleOAuthCallback` tiếp nhận `code` và `state` thật.
- [x] Tinh chỉnh [CreatorClaimResolutionService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorClaimResolutionService.java): gán `verificationMethod = "OAUTH_OFFICIAL_SANDBOX"`, đối soát username TikTok (@) và YouTube Channel ID (UC...).

### Phase 3: Admin Identity Conflict Resolution Endpoint
- [ ] Mở endpoint `POST /admin/creator-conflicts/{id}/resolve` trong [AdminIdentityConflictController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/AdminIdentityConflictController.java).
- [ ] Kết nối tới `CreatorIdentityConflictService.resolveConflict()` và kiểm thử phân quyền `RESOLVE_CREATOR_DUPLICATE`.

### Phase 4: Shortlist Cart to CRM Outreach Bridging
- [ ] Tạo DTO `ShortlistOutreachRequest` và `ShortlistOutreachResponse`.
- [ ] Thêm endpoint `POST /campaigns/{campaignId}/shortlist/outreach` trong [ShortlistController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/controller/ShortlistController.java).
- [ ] Viết logic trong `ShortlistService`: chuyển Shortlist item sang `CONTACTED`, tạo `BrandCreatorRelationship`, và dispatch email mời claim nếu kênh chưa claim.

### Phase 5: Creator Portal Analytics & Multi-Channel Reach
- [ ] Tạo DTO `AggregatedReachDto` chứa tổng followers, views, ER bình quân và chi tiết kênh.
- [ ] Thêm endpoint `GET /creators/portal/analytics/reach` trong [CreatorPortalAccountController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorPortalAccountController.java).

### Phase 6: Automated Acceptance Test Suite
- [ ] Kiểm tra hợp đồng an toàn dữ liệu crawler: `java_mapper.py` (`sourceType = 'PUBLIC'`, `masterId = None`).
- [ ] Xây dựng test suite `Flow4ComprehensiveAcceptanceTest.java` với 10 kịch bản kiểm thử (`TEST-F4-01` đến `TEST-F4-10`).
- [ ] Chạy kiểm thử tự động với Maven (`$env:MAVEN_OPTS="-XX:TieredStopAtLevel=1"; mvn test -Dtest=Flow4ComprehensiveAcceptanceTest`).
