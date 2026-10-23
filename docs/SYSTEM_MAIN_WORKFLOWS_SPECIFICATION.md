# 📑 HỆ THỐNG ĐẶC TẢ 7 MAIN WORKFLOWS & DANH MỤC FUNCTION TOÀN DIỆN
> **Dự án**: InfluencerMatch AI (Hệ thống kết nối và quản trị chiến dịch Influencer/KOC)  
> **Kiến trúc**: Microservices Hybrid (Java Spring Boot Core Backend + Python FastAPI AI Engine + Celery Workers + PostgreSQL + Redis)  
> **Tiêu chuẩn nghiệp vụ**: Đồ án tốt nghiệp Kỹ thuật phần mềm (SEP490 - Capstone Project)

---

## 📌 ĐỊNH NGHĨA CHUẨN MỰC VỀ MAIN WORKFLOW (RUBRIC CRITERIA)

Một **Main Workflow** hợp lệ trong hệ thống phải thỏa mãn đồng thời 3 tiêu chí cốt lõi:
1. **Chu trình nghiệp vụ trọn vẹn**: Khởi đầu từ hành động của một Actor $\rightarrow$ Đi qua chuỗi tương tác xử lý nghiệp vụ $\rightarrow$ Kết thúc bằng kết quả nghiệp vụ cụ thể.
2. **Độ phức tạp kiến trúc**: Có **ít nhất 2 Actor** tham gia HOẶC đi qua **ít nhất 3 Tính năng bắt buộc**.
3. **Biến đổi trạng thái**: Phải làm **thay đổi trạng thái của ít nhất 1 Thực thể chính (Major Entity)** trong cơ sở dữ liệu.

---

# 🗺️ TỔNG QUAN 7 PHÂN HỆ VÀ 7 MAIN WORKFLOWS

```
HỆ THỐNG INFLUENCERMATCH AI (7 MAIN WORKFLOWS)
├── FLOW 1: Brand & Campaign Setup (Khởi tạo Thương hiệu & Chiến dịch Ngữ cảnh)
├── FLOW 2: Creator Discovery & Public Intelligence (Thu thập, Làm sạch & Khám phá KOC)
├── FLOW 3: AI Recommendation using Four-Memory Framework (Đề xuất Thông minh & Tiến hóa Bối cảnh)
├── FLOW 4: Shortlist, Contact & Basic CRM (Định danh Master M1 & Quản trị Phễu Đàm phán)
├── FLOW 5: Campaign Collaboration & Outcome (Thực thi Hợp tác, Nộp Bằng chứng & Nghiệm thu ROI)
├── FLOW 6: Post-Campaign Learning (Chưng cất Tri thức & Tự động Học hỏi Sau Chiến dịch)
└── FLOW 7: Subscription & Payment Management (Mua Gói Dịch vụ, Xử lý VNPay & Quota Provisioning)
```

---

# 🔄 CHI TIẾT 7 MAIN WORKFLOWS VÀ MA TRẬN FUNCTION

---

## 🚀 FLOW 1: Brand & Campaign Setup (Khởi tạo Thương hiệu & Chiến dịch Ngữ cảnh)

### 1. Luồng nghiệp vụ trọn vẹn
Brand đăng ký tài khoản $\rightarrow$ Khởi tạo `BrandProfile` $\rightarrow$ Tự động sinh `BrandContext` (Memory 1) $\rightarrow$ Tạo chiến dịch mới $\rightarrow$ Thẩm định hạn mức gói cước $\rightarrow$ Kích hoạt `Campaign` $\rightarrow$ Tự động sinh `CampaignContext` v1 (Memory 2).

### 2. Biến đổi trạng thái thực thể chính
* `BrandProfile`: Tạo mới $\rightarrow$ `ACTIVE`
* `BrandContext`: Tạo mới $\rightarrow$ `INITIALIZED`
* `Subscription`: `used_campaign_quota` $+ 1$
* `Campaign`: `DRAFT` $\rightarrow$ `ACTIVE`
* `CampaignContext`: Tạo mới $\rightarrow$ `version = 1, active = true`

### 3. Chi tiết Function & Phân bổ Repository

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật & Đề xuất hành động |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **1.1** | `POST /brands/profile`<br>([BrandController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/brand/controller/BrandController.java)) | Brand User | `SEP_BE` | **In**: `CreateBrandProfileRequest`<br>**Out**: `BrandResponse` | 🟢 **ĐÃ LÀM** | Tạo `BrandProfile` và tự động kích hoạt Bộ nhớ 1 `BrandContext` ban đầu. |
| **1.2** | `GET /brands/profile/me`<br>([BrandController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/brand/controller/BrandController.java)) | Brand User | `SEP_BE` | **In**: Bearer Token<br>**Out**: `BrandResponse` | 🟢 **ĐÃ LÀM** | Xem thông tin hồ sơ Brand kèm dữ liệu bối cảnh thương hiệu đang lưu. |
| **1.3** | `PUT /brands/profile`<br>([BrandController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/brand/controller/BrandController.java)) | Brand User | `SEP_BE` | **In**: `UpdateBrandProfileRequest`<br>**Out**: `BrandResponse` | 🟡 **CẦN HOÀN THIỆN** | Cập nhật hồ sơ Brand $\rightarrow$ Tự động đồng bộ cập nhật trường `context_data` trong `BrandContext`. |
| **1.4** | `POST /campaigns`<br>([CampaignController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/campaign/controller/CampaignController.java)) | Brand User, Quota Guard | `SEP_BE` | **In**: `CreateCampaignRequest`<br>**Out**: `CampaignResponse` | 🟢 **ĐÃ LÀM** | Kiểm tra `used_campaign_quota`, lưu `campaign`, tự động sinh `CampaignContext` phiên bản 1. |
| **1.5** | `GET /campaigns/{id}`<br>([CampaignController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/campaign/controller/CampaignController.java)) | Brand User | `SEP_BE` | **In**: `campaignId`<br>**Out**: `CampaignResponse` | 🟢 **ĐÃ LÀM** | Xem chi tiết chiến dịch, nạp bối cảnh vào các màn hình Discovery và AI. |
| **1.6** | `PATCH /campaigns/{id}/status`<br>([CampaignController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/campaign/controller/CampaignController.java)) | Brand User | `SEP_BE` | **In**: `{"status": "PAUSED"}`<br>**Out**: `CampaignResponse` | 🟢 **ĐÃ LÀM** | Chuyển đổi trạng thái chiến dịch: `ACTIVE`, `PAUSED`, `COMPLETED`, `ARCHIVED`. |
| **1.7** | *Campaign Validation Guard* | Hệ thống | `SEP_BE` | **In**: DTO Validation | 🟢 **ĐÃ LÀM** | Ràng buộc logic: `startDate <= endDate` và `budgetMin <= budgetMax`. |

---

## 🔎 FLOW 2: Creator Discovery & Public Intelligence (Thu thập, Làm sạch & Khám phá KOC)

### 1. Luồng nghiệp vụ trọn vẹn
Bot cào tự động quét mạng xã hội $\rightarrow$ AI đọc nội dung phân loại thực thể $\rightarrow$ Loại bỏ Shop bán hàng, cơ quan nhà nước, tài khoản cá nhân $\rightarrow$ Sinh Vector BKAI 768 chiều & tóm tắt AI sạch $\rightarrow$ Ingestion nạp vào Data Pool $\rightarrow$ Data Manager kiểm duyệt dữ liệu tự tin thấp $\rightarrow$ Brand tìm kiếm Discovery theo Hybrid Search (Vector + FTS) và xuất báo cáo.

### 2. Biến đổi trạng thái thực thể chính
* `Creator`: Tạo mới $\rightarrow$ `source_type = 'PUBLIC'`, `master_id = NULL`
* `PublicCreatorMetric`: Tạo mới $\rightarrow$ `FRESH` (sau review cập nhật `quality_reviewed_at = NOW()`)
* `Post`: Lưu trữ danh sách 5-10 bài viết gần nhất phục vụ tra cứu ngữ cảnh

### 3. Chi tiết Function & Phân bổ Repository

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật & Đề xuất hành động |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **2.1** | `TikTokCrawler._crawl_tiktok_profile_sync()`<br>(`app/crawlers/tiktok_crawler.py`) | Crawler Engine | `AI` | **In**: `username`<br>**Out**: `IngestPayload` | 🟢 **ĐÃ LÀM** | Cào Followers, Bio, Recent Posts; bỏ qua nếu `< 7000` followers hoặc tài khoản rỗng. |
| **2.2** | `FacebookCrawler` & `InstagramCrawler`<br>(`app/crawlers/`) | Crawler Engine | `AI` | **In**: `username/url`<br>**Out**: `IngestPayload` | 🟢 **ĐÃ LÀM** | Cào dữ liệu fanpage FB và profile IG; đánh dấu `isStableExternalId = False` với FB. |
| **2.3** | `AIAnalyzer.process_ai_enrichment()`<br>(`app/ai/analyzer.py`) | AI Engine | `AI` | **In**: Bio, Captions<br>**Out**: `AIEnrichment` | 🟢 **ĐÃ LÀM** | Đã mở rộng từ khóa nhận diện `BRAND` (cơ quan nhà nước, báo chí, đài truyền hình, xưởng may). |
| **2.4** | `EmbeddingService.get_search_embedding()`<br>(`app/ai/services/embedding_service.py`) | AI Engine | `AI` | **In**: Text KOC<br>**Out**: `vector(768)` | 🟢 **ĐÃ LÀM** | Mô hình BKAI Bi-Encoder sinh vector 768 chiều phục vụ tìm kiếm ngữ nghĩa. |
| **2.5** | `JavaMapper.from_ingest_payload()`<br>(`app/mappers/java_mapper.py`) | Data Mapper | `AI` | **In**: `IngestPayload`<br>**Out**: Java JSON | 🟢 **ĐÃ LÀM** | Đã map `contentSummary` AI sạch sẽ (không lấy bio rác), format chuẩn `observedAt`. |
| **2.6** | `Celery Worker Tasks`<br>(`app/workers/tasks.py`) | Celery Worker | `AI` | **In**: Celery Queue<br>**Out**: Task Result | 🟢 **ĐÃ LÀM** | **Chốt chặn nghiêm ngặt**: Chỉ gửi `KOL` và `KOC`; hủy không gửi nếu là `BRAND` hoặc `USER`. |
| **2.7** | `POST /creators/ingest`<br>([CreatorIngestionService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorIngestionService.java)) | Ingestion Guard | `SEP_BE` | **In**: `CreatorIngestionRequest`<br>**Out**: `status: SUCCESS` | 🟢 **ĐÃ LÀM** | Advisory Lock, Gatekeeper chặn non-KOL/KOC, nạp `source_type = 'PUBLIC', master_id = NULL`. |
| **2.8** | `GET /admin/data-quality/pending`<br>([AdminDataQualityController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/AdminDataQualityController.java)) | Data Manager | `SEP_BE` | **In**: `page, size`<br>**Out**: `Page<QualityItem>` | 🟢 **ĐÃ LÀM** | Hàng đợi cho Data Manager duyệt các KOC có độ tự tin phân loại thấp (`confidence < 0.7`). |
| **2.9** | `PUT /admin/data-quality/{id}/approve`<br>([AdminDataQualityController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/AdminDataQualityController.java)) | Data Manager | `SEP_BE` | **In**: `{"niche": "..."}`<br>**Out**: `QualityItem` | 🟢 **ĐÃ LÀM** | Đính chính Niche, gán `quality_reviewed_by` và ghi Audit Log. |
| **2.10** | `GET /campaigns/{id}/discovery`<br>([CreatorDiscoveryService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/discovery/service/CreatorDiscoveryService.java)) | Brand User | `SEP_BE` | **In**: `campaignId, keyword`<br>**Out**: `Page<DiscoveryCard>` | 🟢 **ĐÃ LÀM** | Hybrid Search (0.6 Semantic + 0.4 FTS), gom nhóm distinct theo Master M1. |
| **2.11** | *Bộ lọc nâng cao trên Discovery* | Brand User | `SEP_BE` | **In**: `claimStatus, location, tier` | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Bổ sung param: `claimStatus` (`PUBLIC` / `LINKED`), vị trí địa lý, phân cấp Nano/Micro/Macro. |
| **2.12** | `GET /campaigns/{id}/discovery/export` | Brand User | `SEP_BE` | **In**: `campaignId, format=EXCEL`<br>**Out**: File Byte Array | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Xuất danh sách KOC tiềm năng ra file Excel/CSV kèm metrics và Rate Card để trình duyệt ngân sách. |
| **2.13** | `POST /api/v1/crawl/on-demand`<br>(`main.py`) | KOC / System | `AI` | **In**: `{"platform": "TIKTOK", "username": "..."}` | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Cào tức thì khi KOC liên kết kênh mới tinh ở Case 2 (không cần chờ bot quét định kỳ). |

---

## 🧠 FLOW 3: AI Recommendation using Four-Memory Framework (Đề xuất KOC & Tiến hóa Bối cảnh)

### 1. Luồng nghiệp vụ trọn vẹn
Brand bấm *"Đề xuất KOC bằng AI"* $\rightarrow$ Kiểm tra & trừ quota AI $\rightarrow$ Nạp 4 tầng bộ nhớ $\rightarrow$ AI Reranker tính điểm tương thích kèm lý giải (Reasoning) $\rightarrow$ Brand Chấp nhận (vào Shortlist) hoặc Bác bỏ (nhập lý do) $\rightarrow$ Cập nhật phản hồi vào `CampaignContext` (tăng version ngữ cảnh).

### 2. Biến đổi trạng thái thực thể chính
* `Subscription`: `used_ai_quota` $+ 1$
* `ShortlistItem`: Tạo mới bản ghi KOC được Brand lựa chọn vào Shortlist
* `CampaignContext`: `version += 1`, cập nhật ma trận phản hồi (feedback matrix)

### 3. Chi tiết Function & Phân bổ Repository

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật & Đề xuất hành động |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **3.1** | `SubscriptionService.checkAndDeductQuota()` | Quota Guard | `SEP_BE` | **In**: `AI_RECOMMENDATION`<br>**Out**: `void / 403` | 🟢 **ĐÃ LÀM** | Trừ hạn mức AI trong Subscription của Brand trước khi gọi model. |
| **3.2** | `BrandContextRepository` & `CampaignContextRepository` | Backend Engine | `SEP_BE` | **In**: `brandId, campaignId`<br>**Out**: Context entities | 🟢 **ĐÃ LÀM** | Truy xuất Bộ nhớ 1 (`BrandContext`) và Bộ nhớ 2 (`CampaignContext`). |
| **3.3** | `HistoricalCampaignRepository` & `BrandPatternRepository` | Backend Engine | `SEP_BE` | **In**: `brandId, niche`<br>**Out**: Pattern entities | 🟢 **ĐÃ CÓ SCHEMA** | Đã có Flyway table và JPA Entity cho Bộ nhớ 3 & Bộ nhớ 4. |
| **3.4** | `POST /campaigns/{id}/ai-recommendations` | Brand User | `SEP_BE` | **In**: `campaignId, topK = 10`<br>**Out**: `List<RecommendedDto>` | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Viết Service gom 4 bộ nhớ thành 1 JSON payload gửi sang AI Microservice. |
| **3.5** | `POST /api/v1/ai/recommend`<br>(`app/ai/ai_router.py`) | AI Reranker | `AI` | **In**: 4-Memory JSON<br>**Out**: Ranked KOCs + Reasoning | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Viết thuật toán Reranker (Bi-Encoder kết hợp trọng số Pattern) và sinh text lý giải độ phù hợp. |
| **3.6** | *AI Explainable Reasoning Card* | Brand User | `AI` & `BE` | **In**: KOC Features<br>**Out**: `reasoning: String` | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Hiển thị thẻ lý giải: *"KOC phù hợp 92% vì cùng ngách Skincare và từng đạt ROI > 2.5 trong quá khứ"*. |
| **3.7** | `POST /campaigns/{id}/ai-recommendations/feedback` | Brand User | `SEP_BE` | **In**: `creatorId, status, note`<br>**Out**: `ApiResponse.ok()` | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Nhận phản hồi Brand, tự thêm KOC vào Shortlist (nếu Accept), nâng cấp version ngữ cảnh. |
| **3.8** | *Negative Constraints Adaptation* | Machine Learning | `SEP_BE` | **In**: `feedbackNote: "giá cao"` | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Tự động ghi nhận trần ngân sách hoặc loại trừ KOC bị từ chối vào `CampaignContext`. |

---

## 🤝 FLOW 4: Shortlist, Contact & Basic CRM (Định danh Master M1 & Quản trị Phễu Đàm phán)

### 1. Luồng nghiệp vụ trọn vẹn
Brand so sánh side-by-side KOC trong Shortlist $\rightarrow$ Gửi lời mời Claim Kênh công khai $\rightarrow$ KOC xác thực OAuth Proof-of-Control $\rightarrow$ Tạo nguyên tử `CreatorMaster` M1 và chuyển kênh sang `AUTHORIZED` $\rightarrow$ KOC kết nối thêm kênh đa nền tảng $\rightarrow$ Xử lý tranh chấp sở hữu kênh (nếu có) $\rightarrow$ Đàm phán qua phễu CRM theo State Machine $\rightarrow$ Chốt chi phí hợp tác `agreed_fee`.

### 2. Biến đổi trạng thái thực thể chính
* `CreatorInvitation`: `PENDING` $\rightarrow$ `ACCEPTED`
* `CreatorMaster`: Tạo mới thực thể M1 trạng thái `ACTIVE`
* `Creator`: `source_type = 'PUBLIC'` $\rightarrow$ `AUTHORIZED`, gán `master_id = M1`
* `CreatorIdentityConflict`: Tạo mới `PENDING_MANAGER` $\rightarrow$ `RESOLVED` (nếu có tranh chấp)
* `Relationship`: `IDENTIFIED` $\rightarrow$ `CONTACTED` $\rightarrow$ `NEGOTIATING` $\rightarrow$ `AGREED`, chốt `agreed_fee`

### 3. Chi tiết Function & Phân bổ Repository

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật & Đề xuất hành động |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **4.1** | `POST /campaigns/{id}/shortlist/compare`<br>([ShortlistCompareController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/controller/ShortlistCompareController.java)) | Brand User | `SEP_BE` | **In**: `List<creatorId>`<br>**Out**: `CompareResponse` | 🟢 **ĐÃ LÀM** | So sánh đa KOC song song theo metrics mới nhất và Rate Card do KOC công bố. |
| **4.2** | `POST /creators/claim/invitation`<br>([CreatorInvitationService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorInvitationService.java)) | Brand User | `SEP_BE` | **In**: `creatorId, email`<br>**Out**: `InvitationResponse` | 🟢 **ĐÃ LÀM** | Sinh token ngẫu nhiên 64 ký tự, băm SHA-256 lưu DB (hạn 7 ngày). |
| **4.3** | *Gửi Email Lời Mời Thật (JavaMailSender)* | Core Backend | `SEP_BE` | **In**: Email Template HTML | 🟡 **CẦN HOÀN THIỆN** | Cấu hình SMTP gửi email HTML có nút bấm xác nhận thay vì in link ra console log. |
| **4.4** | `GET /creators/claim/invitation/verify`<br>([CreatorInvitationService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorInvitationService.java)) | KOC User | `SEP_BE` | **In**: `?token=...`<br>**Out**: `VerifyResponse` | 🟢 **ĐÃ LÀM** | Endpoint công khai cho KOC xem trước hồ sơ kênh từ link email trước khi claim. |
| **4.5** | `GET /creators/claim/oauth/{platform}/start`<br>([CreatorClaimOAuthController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorClaimOAuthController.java)) | KOC User | `SEP_BE` | **In**: `platform, token`<br>**Out**: 302 Redirect URL | 🟢 **ĐÃ LÀM** | Khởi tạo OAuth ủy quyền kênh với TikTok/Google. |
| **4.6** | `POST /creators/claim`<br>([CreatorClaimResolutionService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorClaimResolutionService.java)) | KOC User | `SEP_BE` | **In**: `code, token`<br>**Out**: `OAuthResponse` | 🟢 **ĐÃ LÀM** | Đối soát kênh; lệch kênh báo `409`; đúng kênh tạo Master M1, đồng bộ Shortlist/CRM. |
| **4.7** | `GET /creators/accounts`<br>([CreatorPortalAccountController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorPortalAccountController.java)) | KOC User | `SEP_BE` | **In**: Bearer KOC<br>**Out**: `List<LinkedAccount>` | 🟢 **ĐÃ LÀM** | Xem danh sách các kênh mạng xã hội đã liên kết thuộc Master M1. |
| **4.8** | `POST /creators/accounts/link`<br>([CreatorAccountLinkService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorAccountLinkService.java)) | KOC User | `SEP_BE` | **In**: `authCode, platform`<br>**Out**: `ConnectResponse` | 🟢 **ĐÃ LÀM** | Xử lý 4 kịch bản liên kết kênh (Kênh có sẵn / Kênh mới / Gia hạn token / Tranh chấp). |
| **4.9** | `GET /creators/portal/analytics/reach` | KOC User | `SEP_BE` | **In**: Bearer KOC<br>**Out**: `TotalReachDto` | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Tính tổng độ phủ (Aggregated Reach) gộp của cả 3 kênh TikTok + FB + YT thuộc Master M1. |
| **4.10** | `PUT /creators/portal/rate-card` | KOC User | `SEP_BE` | **In**: `RateCardRequest`<br>**Out**: `RateCardDto` | 🟡 **CẦN HOÀN THIỆN** | Cho phép KOC tự cấu hình đơn giá video review, livestream, hoa hồng affiliate trên Portal. |
| **4.11** | `GET /admin/creator-conflicts`<br>([AdminIdentityConflictController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/AdminIdentityConflictController.java)) | Data Manager | `SEP_BE` | **In**: `status, page, size`<br>**Out**: `Page<ConflictDto>` | 🟢 **ĐÃ LÀM** | Hàng đợi cho Data Manager xem các tranh chấp quyền sở hữu kênh. |
| **4.12** | `POST /admin/creator-conflicts/{id}/resolve`<br>([CreatorIdentityConflictService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorIdentityConflictService.java)) | Data Manager | `SEP_BE` | **In**: `decision, notes`<br>**Out**: `ConflictDto` | 🟢 **ĐÃ LÀM** | Phê duyệt chuyển kênh (`APPROVE_TRANSFER`) hoặc từ chối (`REJECT`); ghi `CreatorIdentityAudit`. |
| **4.13** | `POST /relationships` & `PATCH /relationships/{id}/stage`<br>([RelationshipController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/controller/RelationshipController.java)) | Brand User | `SEP_BE` | **In**: `stage, agreedFee`<br>**Out**: `RelationshipResponse` | 🟢 **ĐÃ LÀM** | State Machine quản lý CRM: `IDENTIFIED` $\rightarrow$ `CONTACTED` $\rightarrow$ `NEGOTIATING` $\rightarrow$ `AGREED`. |

---

## 📝 FLOW 5: Campaign Collaboration & Outcome (Thực thi Hợp tác, Nộp Bằng chứng & Nghiệm thu ROI)

### 1. Luồng nghiệp vụ trọn vẹn
Chuyển quan hệ CRM `AGREED` thành Hợp tác chính thức (`IN_PROGRESS`) $\rightarrow$ KOC xuất bản video/bài viết công khai $\rightarrow$ KOC nộp link bài đăng và ảnh chụp Dashboard $\rightarrow$ Brand thẩm định $\rightarrow$ Nhập số liệu thực tế đo lường (Views, Orders, Doanh thu) $\rightarrow$ Tự động tính ROI và đóng `COMPLETED` $\rightarrow$ Brand đánh giá tín nhiệm KOC.

### 2. Biến đổi trạng thái thực thể chính
* `Collaboration`: Tạo mới `IN_PROGRESS` $\rightarrow$ `COMPLETED` (`completed_at = NOW()`)
* `EvidenceAsset`: Tạo mới các bản ghi bằng chứng link bài và ảnh chụp
* `CampaignOutcome`: Tạo mới bản ghi tài chính và đo lường ROI
* `CreatorReview`: Tạo mới bản ghi đánh giá tín nhiệm KOC

### 3. Chi tiết Function & Phân bổ Repository

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật & Đề xuất hành động |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **5.1** | `POST /collaborations`<br>([CollaborationController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)) | Brand User | `SEP_BE` | **In**: `relationshipId`<br>**Out**: `CollabResponse` | 🟢 **ĐÃ LÀM** | Kích hoạt hợp tác chính thức `status = IN_PROGRESS`, khóa chống tạo trùng lặp. |
| **5.2** | *Cột mốc thanh toán đợt 1 (`DEPOSIT_PAID`)* | Brand & KOC | `SEP_BE` | **In**: `paymentMilestone` | 🟡 **CẦN HOÀN THIỆN** | Hỗ trợ mốc tạm ứng 50% khi bấm ký hợp tác và quyết toán 50% khi nghiệm thu. |
| **5.3** | `GET /collaborations/{id}`<br>([CollaborationController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)) | Brand & KOC | `SEP_BE` | **In**: `collaborationId`<br>**Out**: `CollabResponse` | 🟢 **ĐÃ LÀM** | Xem chi tiết hợp tác, theo dõi tiến độ và trạng thái thanh toán. |
| **5.4** | `POST /collaborations/{id}/evidence`<br>([EvidenceAssetController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/EvidenceAssetController.java)) | KOC User | `SEP_BE` | **In**: `assetType, storageUrl`<br>**Out**: `EvidenceResponse` | 🟢 **ĐÃ LÀM** | KOC nộp bằng chứng: link bài đăng (`POST_LINK`) hoặc ảnh chụp dashboard (`SCREENSHOT`). |
| **5.5** | *Autonomous Post KPI Auto-Tracking* | AI Crawler | `AI` | **In**: `postUrl`<br>**Out**: Actual views/likes | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Crawler tự động cào lại URL bài đăng sau 3-7 ngày để đối soát view thật với số liệu KOC nộp. |
| **5.6** | `POST /collaborations/{id}/outcome`<br>([CollaborationController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)) | Brand User | `SEP_BE` | **In**: `RecordOutcomeRequest`<br>**Out**: `OutcomeResponse` | 🟢 **ĐÃ LÀM** | Nhập views, clicks, orders, revenue; tự động tính ROI; đóng hợp tác `COMPLETED`. |
| **5.7** | `POST /collaborations/{id}/reviews`<br>([CreatorReviewController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CreatorReviewController.java)) | Brand User | `SEP_BE` | **In**: `overallRating, note`<br>**Out**: `ReviewResponse` | 🟢 **ĐÃ LÀM** | Lưu đánh giá sao, mức độ phù hợp (`brandFitRating`) của Brand dành cho KOC. |

---

## 📈 FLOW 6: Post-Campaign Learning (Chưng cất Tri thức & Tự động Học hỏi Sau Chiến dịch)

### 1. Luồng nghiệp vụ trọn vẹn
Sự kiện nghiệm thu chiến dịch hoàn tất $\rightarrow$ Hệ thống tự động chuyển bằng chứng thực chiến vào Bộ nhớ 3 `HistoricalCampaign` $\rightarrow$ AI Engine chưng cất tri thức (Knowledge Distillation) $\rightarrow$ Cập nhật và nâng cao trọng số thành công vào Bộ nhớ 4 `BrandPattern` (tăng `evidence_count` và `confidence`).

### 2. Biến đổi trạng thái thực thể chính
* `HistoricalCampaign`: Tạo mới bản ghi chứng thực thực tế (`evidence_quality = VERIFIED`)
* `BrandPattern`: Tăng trọng số thành công, nâng cao độ tin cậy tri thức (`confidence`), gán `learned_at = NOW()`

### 3. Chi tiết Function & Phân bổ Repository

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật & Đề xuất hành động |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **6.1** | `HistoricalCampaign` & `BrandPattern` Entity | Backend Core | `SEP_BE` | **DB Schema** | 🟢 **ĐÃ CÓ SCHEMA** | Bảng cơ sở dữ liệu và Model JPA cho Memory 3 và Memory 4 đã sẵn sàng. |
| **6.2** | `CampaignCompletedEventListener`<br>*(Event-Driven Asynchronous)* | Backend Core | `SEP_BE` | **In**: `CampaignCompletedEvent`<br>**Out**: Async Execution | 🟡 **CẦN HOÀN THIỆN** | Sử dụng `@Async` lắng nghe sau Review để quá trình chưng cất chạy nền không làm chậm UI. |
| **6.3** | `LearningService.distillKnowledge()` | Backend Core | `SEP_BE` | **In**: `outcomeId, reviewId`<br>**Out**: Cập nhật DB | 🟡 **CẦN HOÀN THIỆN** | Lưu bằng chứng vào `HistoricalCampaign`, gọi AI Microservice cập nhật `BrandPattern`. |
| **6.4** | `POST /api/v1/ai/distill-patterns`<br>(`app/ai/ai_router.py`) | AI Engine | `AI` | **In**: Outcome + Review<br>**Out**: Extracted Patterns | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Viết endpoint AI phân tích tương quan Niche, KOC Style và ROI để cập nhật trọng số tri thức. |
| **6.5** | *Pattern Time-Decay Algorithm* | AI Engine | `AI` | **In**: `timestamp, weight`<br>**Out**: Decayed Weight | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Áp dụng hệ số suy giảm trọng số theo thời gian để ưu tiên học hỏi từ các chiến dịch 6 tháng gần nhất. |

---

## 💳 FLOW 7: Subscription & Payment Management (Mua Gói Dịch vụ, Xử lý VNPay & Quota Provisioning)

### 1. Luồng nghiệp vụ trọn vẹn
Brand chọn gói dịch vụ $\rightarrow$ Tạo giao dịch thanh toán `PENDING` $\rightarrow$ Chuyển sang cổng VNPay thanh toán $\rightarrow$ VNPay trả về IPN kèm chữ ký số HMAC-SHA512 $\rightarrow$ Backend đối soát, chuyển `Payment` sang `SUCCESS` $\rightarrow$ Kích hoạt `Subscription` `ACTIVE` $\rightarrow$ Reset và cấp mới toàn bộ hạn mức Quota.

### 2. Biến đổi trạng thái thực thể chính
* `Payment`: `PENDING` $\rightarrow$ `SUCCESS`
* `Subscription`: Tạo mới / Gia hạn `ACTIVE`, cập nhật ngày hết hạn, reset `used_quotas = 0`

### 3. Chi tiết Function & Phân bổ Repository

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật & Đề xuất hành động |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **7.1** | `GET /billing/plans`<br>([PlanController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/billing/controller/PlanController.java)) | Brand User | `SEP_BE` | **In**: None<br>**Out**: `List<PlanDTO>` | 🟢 **ĐÃ LÀM** | Xem danh mục các gói cước dịch vụ (STARTER, PRO, ENTERPRISE). |
| **7.2** | `POST /billing/subscribe`<br>([SubscriptionController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/billing/controller/SubscriptionController.java)) | Brand User | `SEP_BE` | **In**: `{"planId": "..."}`<br>**Out**: `paymentUrl` | 🟢 **ĐÃ LÀM** | Khởi tạo giao dịch `Payment` trạng thái `PENDING`, sinh URL thanh toán VNPay. |
| **7.3** | `GET /billing/vnpay/return` & `POST /billing/vnpay/ipn`<br>([VNPayController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/billing/controller/VNPayController.java)) | VNPay Gateway | `SEP_BE` | **In**: VNPay checksum<br>**Out**: Success / Fail | 🟢 **ĐÃ LÀM** | Thẩm thực chữ ký số HMAC-SHA512 thật từ VNPay, kích hoạt `Subscription` `ACTIVE`. |
| **7.4** | `POST /billing/mock/webhook`<br>([MockPaymentController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/billing/controller/MockPaymentController.java)) | Brand User | `SEP_BE` | **In**: `transactionCode`<br>**Out**: `PaymentResponse` | 🟢 **ĐÃ LÀM** | Mock webhook an toàn cho môi trường test/dev mà không cần thẻ ngân hàng thật. |
| **7.5** | *VNPay Webhook Idempotency Guard* | Core Backend | `SEP_BE` | **In**: Transaction Ref | 🟢 **ĐÃ LÀM** | Khóa chống xử lý IPN trùng lặp từ cổng thanh toán VNPay gửi sang. |
| **7.6** | `SubscriptionService.checkQuota()` | Quota Guard | `SEP_BE` | **In**: `quotaType`<br>**Out**: `void / 403` | 🟢 **ĐÃ LÀM** | Paywall Gatekeeper chặn khi Brand sử dụng vượt quá hạn mức cho phép. |
| **7.7** | *Subscription Expiration Scheduler* | Cronjob | `SEP_BE` | **In**: Daily Trigger `@Scheduled` | 🟡 **CẦN HOÀN THIỆN** | Quét định kỳ hàng ngày, tự động chuyển `Subscription` sang `EXPIRED` khi hết hạn. |

---

# 📊 BẢNG TỔNG KẾT KHỐI LƯỢNG NÂNG CẤP THEO 2 REPO

| Repo | Đã hoàn thành (🟢) | Cần hoàn thiện (🟡) | Đề xuất làm mới (🔴) | Tổng số Functions |
| :---: | :---: | :---: | :---: | :---: |
| **`SEP_BE` (Java Spring Boot)** | **28** Functions | **6** Functions | **4** Functions | **38** Functions |
| **`creator-discovery` (Python AI)** | **6** Functions | **0** Functions | **5** Functions | **11** Functions |
| **TỔNG CỘNG CẢ HỆ THỐNG** | **34** (70%) | **6** (12%) | **9** (18%) | **49** Functions |

---

# 🏆 BẢNG KIỂM TRA ĐỘ HỢP LỆ THEO 3 TIÊU CHÍ BẮT BUỘC (RUBRIC COMPLIANCE)

| Main Flow | Tên Luồng Nghiệp Vụ Chính | Các Actor Tham Gia | Số Tính Năng Bắt Buộc | Thực Thể Chính Đổi Trạng Thái | Đánh Giá Hợp Lệ |
| :---: | :--- | :--- | :---: | :--- | :---: |
| **FLOW 1** | Brand & Campaign Setup | Brand User, Backend, Quota Guard | 4 tính năng | `BrandProfile`, `BrandContext`, `Subscription`, `Campaign`, `CampaignContext` | ✅ **ĐẠT CHUẨN 100%** |
| **FLOW 2** | Creator Discovery & Public Intel | Crawler, AI Engine, Admin, Brand | 5 tính năng | `Creator`, `PublicCreatorMetric`, `Post` | ✅ **ĐẠT CHUẨN 100%** |
| **FLOW 3** | Four-Memory AI Recommendation | Brand User, Backend, AI Engine | 4 tính năng | `Subscription`, `ShortlistItem`, `CampaignContext` | ✅ **ĐẠT CHUẨN 100%** |
| **FLOW 4** | Shortlist, Contact & Basic CRM | Brand, KOC, OAuth, Admin | 5 tính năng | `Invitation`, `CreatorMaster`, `Creator`, `Conflict`, `Relationship` | ✅ **ĐẠT CHUẨN 100%** |
| **FLOW 5** | Campaign Collaboration & Outcome | Brand User, KOC User, Backend | 4 tính năng | `Collaboration`, `EvidenceAsset`, `CampaignOutcome`, `CreatorReview` | ✅ **ĐẠT CHUẨN 100%** |
| **FLOW 6** | Post-Campaign Learning | Core Backend, AI Engine | 3 tính năng | `HistoricalCampaign`, `BrandPattern` | ✅ **ĐẠT CHUẨN 100%** |
| **FLOW 7** | Subscription & Payment Management | Brand User, VNPay, Backend | 4 tính năng | `Payment`, `Subscription` | ✅ **ĐẠT CHUẨN 100%** |
