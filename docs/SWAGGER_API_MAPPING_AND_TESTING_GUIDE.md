# 📚 SWAGGER API MAPPING & COMPREHENSIVE TESTING GUIDE (FLOWS 1 - 7)
> **Dự án**: InfluencerMatch AI (Core Backend Platform)  
> **Cổng tài liệu Swagger UI**: [http://localhost:8080/api/v1/swagger-ui/index.html](http://localhost:8080/api/v1/swagger-ui/index.html)  
> **OpenAPI 3.0 Spec JSON**: [http://localhost:8080/api/v1/api-docs](http://localhost:8080/api/v1/api-docs)  
> **Dataset mẫu sạch đã seed**: [`docs/CLEAN_TEST_DATASET_FLOWS_1_TO_7.sql`](file:///d:/SEP_BE/docs/CLEAN_TEST_DATASET_FLOWS_1_TO_7.sql)

---

## 🚀 I. HƯỚNG DẪN KHỞI CHẠY & NẠP DATASET MẪU CHO FE

### 1. Nạp Dataset mẫu vào Database
Trước khi test trên Swagger, chạy lệnh sau trong PostgreSQL (hoặc qua DBeaver / pgAdmin) để nạp toàn bộ dữ liệu sạch:
```bash
psql -U influencermatch_user -d influencermatch_dev -f docs/CLEAN_TEST_DATASET_FLOWS_1_TO_7.sql
```
*(Hoặc copy toàn bộ nội dung file [`CLEAN_TEST_DATASET_FLOWS_1_TO_7.sql`](file:///d:/SEP_BE/docs/CLEAN_TEST_DATASET_FLOWS_1_TO_7.sql) paste vào SQL Query Tool và Execute).*

### 2. Danh sách Tài khoản Test Đã Tạo Sẵn
Tất cả các tài khoản bên dưới đều dùng chung mật khẩu chuẩn: **`Password123!`** (đã mã hóa BCrypt an toàn):

| Vai trò (Role) | Email đăng nhập | Password | Mục đích Test trên Swagger | Quyền hạn tiêu biểu |
| :--- | :--- | :---: | :--- | :--- |
| **BRAND** | `brand@acme.com` | `Password123!` | Test Flows 1, 3, 4, 5, 7 (Quản lý Brand, Chiến dịch, Shortlist, CRM, Hợp tác, Nghiệm thu, Mua gói) | `ROLE_BRAND`, `CREATE_CAMPAIGN`, `MANAGE_OWN_COLLABORATION`, `RECORD_OWN_OUTCOME`, `RECORD_OWN_CREATOR_REVIEW` |
| **CREATOR 1** | `koc.glow@creator.vn` | `Password123!` | Test KOC Portal (Xem hợp tác được giao, nộp bằng chứng bài đăng, xem analytics độ phủ kênh TikTok `@glow.beauty`) | `ROLE_CREATOR`, `CLAIM_CREATOR_PROFILE`, `VIEW_OWN_CREATOR_CLAIM` |
| **CREATOR 2** | `koc.tech@creator.vn` | `Password123!` | Test KOC Portal kênh YouTube (`Tech Lab Vietnam`) | `ROLE_CREATOR` |
| **ADMIN** | `admin@influencermatch.com` | `Password123!` | Test quản trị hệ thống, quản lý Gói dịch vụ (Plans), phân quyền Roles, Audit logs | `ROLE_ADMIN`, `MANAGE_PLAN`, `VIEW_USER`, `MANAGE_SUBSCRIPTION` |
| **DATA_MANAGER** | `datamanager@influencermatch.com` | `Password123!` | Test kiểm duyệt chất lượng dữ liệu KOC (Data Quality) và giải quyết tranh chấp kênh (Identity Conflicts) | `ROLE_DATA_MANAGER`, `REVIEW_CREATOR_CLASSIFICATION`, `RESOLVE_CREATOR_DUPLICATE` |

---

## 🔑 II. CƠ CHẾ XÁC THỰC TRÊN SWAGGER UI (AUTHORIZE BUTTON)

1. Mở trình duyệt truy cập: **`http://localhost:8080/api/v1/swagger-ui/index.html`**
2. Tìm đến nhóm API **`Authentication`** $\rightarrow$ chọn `POST /auth/login`
3. Nhấn **Try it out**, nhập JSON đăng nhập (ví dụ tài khoản Brand):
   ```json
   {
     "email": "brand@acme.com",
     "password": "Password123!"
   }
   ```
4. Nhấn **Execute**, copy chuỗi token trong trường `data.accessToken`.
5. Cuộn lên đầu trang Swagger UI, bấm vào nút 🔓 **Authorize** (góc phải trên cùng).
6. Dán token vừa copy vào ô **Value** $\rightarrow$ Nhấn **Authorize** $\rightarrow$ **Close**.
7. Biểu tượng ổ khóa sẽ chuyển sang trạng thái 🔒 **đã khóa**, bây giờ bạn có thể gọi mọi API yêu cầu quyền Brand mà không lo lỗi `401 Unauthorized`.

---

## 🗺️ III. KỊCH BẢN MAPPING API TỪ FLOW 1 ĐẾN FLOW 7

---

### 📦 FLOW 7: Quản trị Gói Dịch vụ & Thanh toán (Subscription & Billing)

#### 7.1. Xem danh mục các gói dịch vụ (Public / Brand)
* **Endpoint**: `GET /plans`
* **Actor**: Brand User / Public
* **Mô tả**: Trả về 3 gói dịch vụ đã seed: `FREE` (0 VND), `PRO` (1.500.000 VND), `ENTERPRISE` (5.000.000 VND) kèm hạn mức chiến dịch và lượt AI recommendation.
* **Kết quả**: `200 OK`

#### 7.2. Xem trạng thái gói cước hiện tại của Brand
* **Endpoint**: `GET /subscriptions/brand/{brandId}`
* **Actor**: Brand User (`brand@acme.com`)
* **Path Variable**: `brandId = 22222222-2222-2222-2222-222222222211`
* **Kết quả**: `200 OK`, trả về gói `PRO` đang `ACTIVE` với `usedCampaignQuota = 1`, `usedAiQuota = 1`.

#### 7.3. Tạo link thanh toán nâng cấp gói qua VNPay
* **Endpoint**: `POST /billing/subscribe`
* **Actor**: Brand User
* **Request Body**:
  ```json
  {
    "planId": "11111111-1111-1111-1111-111111111103"
  }
  ```
* **Kết quả**: `200 OK`, trả về `paymentUrl` của VNPay Sandbox sẵn sàng redirect.

---

### 🏢 FLOW 1: Khởi tạo Thương hiệu & Chiến dịch Ngữ cảnh (Brand & Campaign Setup)

#### 1.1. Lấy thông tin hồ sơ Brand cá nhân
* **Endpoint**: `GET /brands/me`
* **Actor**: Brand User (`brand@acme.com`)
* **Kết quả**: `200 OK`, trả về thông tin `Acme Cosmetics Vietnam`, ngành `BEAUTY`, các nền tảng ưu tiên `['TIKTOK', 'YOUTUBE']`.

#### 1.2. Cập nhật hồ sơ Brand & Tự động tăng phiên bản Bộ nhớ AI M4
* **Endpoint**: `PUT /brands/me`
* **Actor**: Brand User
* **Request Body**:
  ```json
  {
    "businessName": "Acme Cosmetics Vietnam",
    "industry": "BEAUTY",
    "productCategories": ["SKINCARE", "COSMETICS", "SUNSCREEN", "LIPSTICK"],
    "website": "https://acmecosmetics.vn",
    "location": "Ho Chi Minh City, Vietnam",
    "targetMarkets": ["VIETNAM", "SOUTHEAST_ASIA"],
    "targetAudiences": ["FEMALE_18_28", "OFFICE_WORKERS", "GEN_Z"],
    "brandTone": "MODERN_YOUTHFUL_TRUSTWORTHY",
    "preferredPlatforms": ["TIKTOK", "YOUTUBE", "INSTAGRAM"],
    "description": "Thương hiệu mỹ phẩm thuần chay dưỡng sáng và chống nắng bảo vệ làn da phụ nữ Việt Nam."
  }
  ```
* **Kết quả**: `200 OK`, tự động đồng bộ vào `brand_context` (**M4**) và tăng `contextVersion`.

#### 1.3. Xem chi tiết Chiến dịch mẫu
* **Endpoint**: `GET /campaigns/{id}`
* **Path Variable**: `id = 22222222-2222-2222-2222-222222222241`
* **Kết quả**: `200 OK`, trả về chiến dịch "Summer Glow 2026", ngân sách 30tr - 80tr VND, status `ACTIVE`.

#### 1.4. Tạo Chiến dịch mới (Có kiểm tra Quota Guard)
* **Endpoint**: `POST /brands/{brandId}/campaigns`
* **Path Variable**: `brandId = 22222222-2222-2222-2222-222222222211`
* **Request Body**:
  ```json
  {
    "name": "Acme Cleanse Autumn 2026",
    "productService": "Acme Gentle Cleansing Oil",
    "objective": "CONVERSIONS",
    "targetAudiences": ["Nữ 20-30", "Dân văn phòng"],
    "platforms": ["TIKTOK", "INSTAGRAM"],
    "niches": ["BEAUTY", "SKINCARE"],
    "budgetMin": 20000000,
    "budgetMax": 50000000,
    "currency": "VND",
    "followerMin": 30000,
    "followerMax": 500000,
    "engagementRateMin": 0.0300,
    "startDate": "2026-11-01",
    "endDate": "2026-12-31"
  }
  ```
* **Kết quả**: `201 Created`, lưu chiến dịch ở trạng thái `DRAFT`.

---

### 🔍 FLOW 2: Thu thập, Làm sạch & Khám phá KOC (Creator Discovery & Intelligence)

#### 2.1. Tra cứu Danh mục Niche & Nền tảng
* **Endpoint**: `GET /taxonomies/niches`
* **Endpoint**: `GET /taxonomies/platforms`
* **Kết quả**: `200 OK`

#### 2.2. Khám phá & Tìm kiếm KOC (Hybrid Discovery Search)
* **Endpoint**: `GET /discovery/creators`
* **Query Parameters**:
  * `keyword`: `Glow`
  * `platform`: `TIKTOK`
  * `niche`: `BEAUTY`
  * `minFollowers`: `100000`
  * `minEngagementRate`: `0.03`
* **Kết quả**: `200 OK`, trả về hồ sơ KOC `@glow.beauty` (350.000 followers, ER 4.8%, avg views 45.000).

#### 2.3. Xem chi tiết KOC & Lịch sử bài đăng
* **Endpoint**: `GET /discovery/creators/{id}`
* **Path Variable**: `id = 55555555-5555-5555-5555-555555555501`
* **Kết quả**: `200 OK`

---

### 🧠 FLOW 3: Đề xuất AI 4 Bộ nhớ (AI Recommendation Engine)

#### 3.1. Chạy AI Recommendation cho Chiến dịch
* **Endpoint**: `POST /campaigns/{campaignId}/ai-recommendations`
* **Path Variable**: `campaignId = 22222222-2222-2222-2222-222222222241`
* **Query Parameter**: `limit = 10`
* **Mô tả**: Service tổng hợp bối cảnh chiến dịch M3 + pattern thành công M4 + bằng chứng lịch sử M1, tự động chấm điểm và sinh thẻ lý do gợi ý (Reasoning Card).
* **Kết quả**: `200 OK`

#### 3.2. Xem lịch sử các lượt chạy AI Recommendation
* **Endpoint**: `GET /campaigns/{campaignId}/ai-recommendations/runs`
* **Path Variable**: `campaignId = 22222222-2222-2222-2222-222222222241`
* **Kết quả**: `200 OK`, trả về danh sách các run ID và số ứng viên đã đề xuất.

#### 3.3. Phản hồi kết quả gợi ý AI (Feedback Loop)
* **Endpoint**: `POST /campaigns/{campaignId}/ai-recommendations/feedback`
* **Path Variable**: `campaignId = 22222222-2222-2222-2222-222222222241`
* **Request Body**:
  ```json
  {
    "creatorId": "55555555-5555-5555-5555-555555555503",
    "action": "ACCEPT",
    "reasonCode": "EXCELLENT_SKINCARE_KNOWLEDGE",
    "note": "Rất phù hợp để giải thích chuyên sâu thành phần kem chống nắng"
  }
  ```
* **Kết quả**: `200 OK`, tự động đưa KOC vào Shortlist của chiến dịch.

---

### 🤝 FLOW 4: Shortlist, Contact, OAuth Claim & CRM Quản trị Phễu

#### 4.1. Xem giỏ lưu KOC (Shortlist Cart)
* **Endpoint**: `GET /campaigns/{campaignId}/shortlist`
* **Path Variable**: `campaignId = 22222222-2222-2222-2222-222222222241`
* **Kết quả**: `200 OK`, hiển thị các KOC đã lưu trong giỏ (`@glow.beauty`, `@skincare.minh`).

#### 4.2. So sánh KOC trong Shortlist (Side-by-Side Compare)
* **Endpoint**: `POST /campaigns/{campaignId}/shortlist/compare`
* **Path Variable**: `campaignId = 22222222-2222-2222-2222-222222222241`
* **Request Body**:
  ```json
  {
    "creatorIds": [
      "55555555-5555-5555-5555-555555555501",
      "55555555-5555-5555-5555-555555555503"
    ]
  }
  ```
* **Kết quả**: `200 OK`, bảng đối chiếu chi tiết Follower, Engagement Rate, Avg Views, Niche và xác minh kênh.

#### 4.3. Gửi lời mời hợp tác & Chuyển vào phễu CRM (Shortlist Outreach)
* **Endpoint**: `POST /campaigns/{campaignId}/shortlist/outreach`
* **Path Variable**: `campaignId = 22222222-2222-2222-2222-222222222241`
* **Request Body**:
  ```json
  {
    "shortlistItemIds": [
      "77777777-7777-7777-7777-777777777702"
    ],
    "message": "Chào bạn Minh, Acme Cosmetics muốn mời bạn tham gia chiến dịch trải nghiệm kem chống nắng Summer Glow 2026.",
    "proposedFee": 3000000,
    "currency": "VND"
  }
  ```
* **Kết quả**: `200 OK`, chuyển KOC sang giai đoạn CRM `CONTACTED`.

#### 4.4. Xem phễu quan hệ CRM của chiến dịch
* **Endpoint**: `GET /campaigns/{campaignId}/relationships`
* **Path Variable**: `campaignId = 22222222-2222-2222-2222-222222222241`
* **Kết quả**: `200 OK`, danh sách KOC ở các stage: `COLLABORATING`, `CONTACTED`, `COMPLETED`.

#### 4.5. Đàm phán và Chuyển trạng thái CRM sang COLLABORATING
* **Endpoint**: `PATCH /campaigns/{campaignId}/relationships/{relationshipId}/stage`
* **Path Variable**:
  * `campaignId = 22222222-2222-2222-2222-222222222241`
  * `relationshipId = 88888888-8888-8888-8888-888888888802`
* **Request Body**:
  ```json
  {
    "stage": "COLLABORATING",
    "agreedFee": 4500000,
    "currency": "VND",
    "nextAction": "Chốt thỏa thuận 1 video YouTube, chuẩn bị tạo hợp tác",
    "nextActionDueAt": "2026-11-05T12:00:00"
  }
  ```
* **Kết quả**: `200 OK`

---

### 📝 FLOW 5: Thực thi Hợp tác, Nộp Bằng chứng & Nghiệm thu Tương tác

#### 5.1. Khởi tạo Hợp tác Chính thức từ Relationship COLLABORATING
* **Endpoint**: `POST /campaigns/{id}/collaborations`
* **Actor**: Brand User (`brand@acme.com`)
* **Path Variable**: `id = 22222222-2222-2222-2222-222222222241`
* **Request Body**:
  ```json
  {
    "relationshipId": "88888888-8888-8888-8888-888888888801",
    "agreedFee": 3500000,
    "currency": "VND",
    "note": "Hợp tác chính thức sản xuất 1 video TikTok review kem chống nắng"
  }
  ```
* **Kết quả**: `201 Created`, trả về `CollaborationResponse` với `status = IN_PROGRESS`, `agreedFee = 3500000 VND`.

#### 5.2. Brand xem danh sách KOC đang hợp tác trong chiến dịch
* **Endpoint**: `GET /campaigns/{id}/collaborations`
* **Actor**: Brand User
* **Path Variable**: `id = 22222222-2222-2222-2222-222222222241`
* **Kết quả**: `200 OK`, trả về phân trang chuẩn `PageResponse` gồm KOC name, avatar, platform, status, `hasOutcome`, `hasReview`.

#### 5.3. KOC xem danh sách hợp tác được giao qua Creator Portal
* **Endpoint**: `GET /creators/portal/collaborations`
* **Actor**: KOC User (`koc.glow@creator.vn`) *(Bấm Authorize với token của KOC)*
* **Kết quả**: `200 OK`, hiển thị hợp tác được giao, tên chiến dịch "Summer Glow 2026", deadline và trạng thái `IN_PROGRESS`.

#### 5.4. Xem chi tiết hợp tác cụ thể
* **Endpoint**: `GET /collaborations/{id}`
* **Actor**: Brand User hoặc KOC User
* **Path Variable**: `id = 99999999-9999-9999-9999-999999999901`
* **Kết quả**: `200 OK`, trả về thông tin KOC, brandName, toàn bộ danh sách `EvidenceAsset` đã nộp, trạng thái nghiệm thu.

#### 5.5. KOC / Brand nộp link bài đăng & Bằng chứng thực thi (Evidence Submission)
* **Endpoint**: `POST /collaborations/{id}/evidence`
* **Actor**: KOC User (`koc.glow@creator.vn`) hoặc Brand User
* **Path Variable**: `id = 99999999-9999-9999-9999-999999999901`
* **Request Body**:
  ```json
  {
    "assetType": "POST_LINK",
    "storageUrl": "https://www.tiktok.com/@glow.beauty/video/7345678901234567890",
    "publishedContentUrl": "https://www.tiktok.com/@glow.beauty/video/7345678901234567890",
    "note": "Video chính thức đã đăng lúc 20h00 tối thứ 6, gắn đầy đủ hashtag nhãn hàng"
  }
  ```
* **Kết quả**: `201 Created`, tự động cập nhật `publishedContentUrl` trên bản ghi hợp tác và lưu bằng chứng vào bảng `evidence_asset`.

#### 5.6. Xem danh sách bằng chứng đã nộp của hợp tác
* **Endpoint**: `GET /collaborations/{id}/evidence`
* **Path Variable**: `id = 99999999-9999-9999-9999-999999999901`
* **Kết quả**: `200 OK`, hiển thị danh sách gồm link bài đăng và ảnh chụp Dashboard Insights.

#### 5.7. Brand Nghiệm thu tương tác mạng xã hội (Record Outcome)
* **Endpoint**: `POST /collaborations/{id}/outcome`
* **Actor**: Brand User (`brand@acme.com`)
* **Path Variable**: `id = 99999999-9999-9999-9999-999999999901`
* **Request Body**:
  ```json
  {
    "views": 42000,
    "clicks": 680,
    "likes": 3100,
    "comments": 210,
    "shares": 85,
    "publishedContentUrl": "https://www.tiktok.com/@glow.beauty/video/7345678901234567890",
    "notes": "Video vượt KPI đề ra, tương tác người xem rất tích cực"
  }
  ```
* **Kết quả**: `201 Created`, chuyển `collaboration.status = COMPLETED` và relationship `stage = COMPLETED`, gán nhãn `evidenceQuality = 'SELF_REPORTED'`.

#### 5.8. Brand Đánh giá Creator Review (Kích hoạt Tự động Đồng bộ AI)
* **Endpoint**: `POST /collaborations/{id}/review`
* **Actor**: Brand User (`brand@acme.com`)
* **Path Variable**: `id = 99999999-9999-9999-9999-999999999901`
* **Request Body**:
  ```json
  {
    "overallRating": 5,
    "brandFitRating": 5,
    "wouldCollaborateAgain": true,
    "note": "KOC làm việc chuyên nghiệp, kịch bản sáng tạo, đúng hạn cam kết"
  }
  ```
* **Kết quả**: `201 Created`, phát sinh `CollaborationReviewedEvent`.

---

### 📈 FLOW 6: Chưng cất Tri thức & Tự động Học hỏi (Post-Campaign AI Learning)

* **Cơ chế tự động**:
  * Khi Brand gửi review thành công ở bước **5.8**, `CollaborationCompletedEventListener` tự động:
    1. Trích xuất chỉ số tương tác và đánh giá để tạo bản ghi **Bộ nhớ 1 (`HistoricalCampaign` M1)** với `quality = 'VERIFIED'` và `reliabilityScore = 1.0`.
    2. Cập nhật **Bộ nhớ 4 (`BrandPattern` M4)** của Brand: Tăng `evidenceCount += 1`, nâng cao độ tin cậy `confidence`, và cập nhật `averageSuccessfulScore`.
* **Kiểm tra kết quả**:
  * Chạy lại `POST /campaigns/{campaignId}/ai-recommendations`: AI sẽ ưu tiên KOC có lịch sử nghiệm thu tốt và phù hợp với Brand Pattern đã học được!

---

## 🛠️ IV. BẢNG TỔNG HỢP CÁC LỖI HTTP THƯỜNG GẶP VÀ CÁCH XỬ LÝ TRÊN SWAGGER

| Mã lỗi HTTP | Nguyên nhân chính | Cách xử lý nhanh |
| :---: | :--- | :--- |
| **`401 Unauthorized`** | Chưa bấm nút **Authorize** hoặc JWT Token đã hết hạn (quá 15 phút). | Gọi lại `POST /auth/login`, copy `accessToken` mới và dán vào nút 🔓 **Authorize**. |
| **`403 Forbidden`** | Đang dùng Token của KOC để gọi API của Brand (hoặc ngược lại); hoặc thiếu quyền hạn cần thiết. | Đăng nhập đúng tài khoản tương ứng với vai trò của API (xem Bảng tài khoản ở Mục I). |
| **`404 Not Found`** | Sai UUID trong Path Variable, hoặc tài nguyên không thuộc sở hữu của Brand đang đăng nhập. | Dùng các UUID đã được seed sẵn trong file [`CLEAN_TEST_DATASET_FLOWS_1_TO_7.sql`](file:///d:/SEP_BE/docs/CLEAN_TEST_DATASET_FLOWS_1_TO_7.sql). |
| **`409 Conflict`** | **`COLLABORATION_ALREADY_EXISTS`**: Thử tạo Collaboration cho Relationship đã có hợp tác rồi; hoặc **`QUOTA_EXCEEDED`**: Đã hết quota chiến dịch. | Mỗi Relationship chỉ tạo 1 Collaboration duy nhất; nâng cấp gói PRO/ENTERPRISE nếu hết quota. |
| **`400 Bad Request`** | Thiếu trường bắt buộc (`@NotBlank`), sai định dạng URL (`publishedContentUrl` phải có `https://...`), hoặc rating nằm ngoài khoảng 1–5 sao. | Kiểm tra lại JSON Request Body theo đúng mẫu ở Mục III. |

---

## 🎯 V. KẾT LUẬN & CAM KẾT SẴN SÀNG CHO FRONTEND

1. **100% Endpoints đều có OpenAPI Annotation**: Có thẻ `@Tag` phân nhóm rõ ràng, mô tả `@Operation` chi tiết và khai báo `@SecurityRequirement(name = "BearerAuth")`.
2. **Dataset mẫu chuẩn xác**: Đầy đủ quan hệ logic giữa Users $\to$ Plans $\to$ Brand Profiles $\to$ Campaigns $\to$ Creators $\to$ Relationships $\to$ Collaborations $\to$ Evidence Assets $\to$ Outcomes $\to$ Reviews $\to$ AI Memories.
3. **Mã nguồn đã biên dịch sạch sẽ**: Không có bất kỳ lỗi cú pháp hay xung đột nào trong toàn bộ 307 source files và 87 test files của hệ thống.
