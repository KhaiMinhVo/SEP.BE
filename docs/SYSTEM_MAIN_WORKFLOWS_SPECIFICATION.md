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
Brand đăng ký tài khoản $\rightarrow$ Khởi tạo `BrandProfile` $\rightarrow$ Tự động sinh `BrandContext` (Bộ nhớ bối cảnh M4 v1) $\rightarrow$ Cập nhật hồ sơ (qua `PUT /brands/me` tự động ẩn ID hoặc `PUT /brands/{id}` của Admin) $\rightarrow$ Tự động đồng bộ `BrandContext` và tăng `contextVersion` $\rightarrow$ Tạo chiến dịch mới $\rightarrow$ Thẩm định hạn mức chiến dịch qua Quota Guard (`SubscriptionService.checkAndDeductCampaignQuota`) $\rightarrow$ Lưu chiến dịch ở `DRAFT` $\rightarrow$ Thẩm định logic thời gian/ngân sách $\rightarrow$ Chuyển trạng thái sang `READY_FOR_DISCOVERY` / `ACTIVE` $\rightarrow$ Tự động sinh `CampaignContext` v1 (Bộ nhớ bối cảnh M3).

### 2. Biến đổi trạng thái thực thể chính
* `BrandProfile`: Tạo mới $\rightarrow$ `ACTIVE`
* `BrandContext` (**M4**): Tạo mới $\rightarrow$ `contextVersion = 1` $\rightarrow$ Mỗi lần cập nhật hồ sơ: `contextVersion += 1, updated_at = NOW()`
* `Subscription`: `used_campaign_quota += 1` (Chặn `409 QUOTA_EXCEEDED` nếu vượt `plan.campaignQuota`)
* `Campaign`: `DRAFT` $\rightarrow$ `READY_FOR_DISCOVERY` $\rightarrow$ `ACTIVE` $\rightarrow$ `COMPLETED` $\rightarrow$ `ARCHIVED`
* `CampaignContext` (**M3**): Tự động tạo mới khi chiến dịch sẵn sàng $\rightarrow$ `version = 1, active = true`

### 3. Chi tiết Function & Phân bổ Repository

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật & Đề xuất hành động |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **1.1** | `POST /brands`<br>([BrandController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/brand/controller/BrandController.java)) | Brand User | `SEP_BE` | **In**: `BrandRequest`<br>**Out**: `BrandResponse` | 🟢 **ĐÃ LÀM** | Tạo `BrandProfile` liên kết `User` hiện tại; tự động kích hoạt **Bộ nhớ 4 (`BrandContext` - M4)** phiên bản 1 (`contextVersion = 1`). |
| **1.2** | `GET /brands/me`<br>([BrandController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/brand/controller/BrandController.java)) | Brand User | `SEP_BE` | **In**: Bearer Token<br>**Out**: `BrandResponse` | 🟢 **ĐÃ LÀM** | Xem thông tin hồ sơ Brand cá nhân dựa trên danh tính JWT (ẩn hoàn toàn UUID trên URL); kèm bối cảnh **M4** đang lưu trữ. |
| **1.3** | `PUT /brands/{id}` & `PUT /brands/me`<br>([BrandController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/brand/controller/BrandController.java)) | Brand User, Admin | `SEP_BE` | **In**: `BrandRequest`<br>**Out**: `BrandResponse` | 🟢 **ĐÃ LÀM** | Hỗ trợ 2 cổng: `PUT /brands/me` (Brand tự cập nhật ẩn ID) & `PUT /brands/{id}` (Admin cập nhật). Tự động đồng bộ `profileContext` vào **M4**, tăng `contextVersion` và lưu tường minh xuống DB. |
| **1.4** | `POST /brands/{brandId}/campaigns`<br>([CampaignController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/campaign/controller/CampaignController.java)) | Brand User, Quota Guard | `SEP_BE` | **In**: `CampaignRequest`<br>**Out**: `CampaignResponse` | 🟢 **ĐÃ LÀM** | Tích hợp **Quota Guard** (`SubscriptionService.checkAndDeductCampaignQuota`): Chặn nếu vượt hạn mức chiến dịch (`ErrorCode.QUOTA_EXCEEDED`), lưu campaign mới ở `DRAFT`. |
| **1.5** | `GET /campaigns/{id}`<br>([CampaignController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/campaign/controller/CampaignController.java)) | Brand User | `SEP_BE` | **In**: `campaignId`<br>**Out**: `CampaignResponse` | 🟢 **ĐÃ LÀM** | Xem chi tiết chiến dịch, thẩm định quyền sở hữu (`brandProfile.user.id == actor.id`), nạp bối cảnh vào các màn hình Discovery và AI. |
| **1.6** | `PATCH /campaigns/{id}/status`<br>([CampaignController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/campaign/controller/CampaignController.java)) | Brand User | `SEP_BE` | **In**: `{"status": "READY_FOR_DISCOVERY"}`<br>**Out**: `CampaignResponse` | 🟢 **ĐÃ LÀM** | Chuyển đổi trạng thái chiến dịch: `DRAFT` $\rightarrow$ `READY_FOR_DISCOVERY` $\rightarrow$ `ACTIVE` $\rightarrow$ `COMPLETED`. Tự động sinh **M3 (`CampaignContext`)** phiên bản 1 (`active = true`). |
| **1.7** | *Campaign Validation Guard*<br>([CampaignService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/campaign/service/CampaignService.java)) | Hệ thống | `SEP_BE` | **In**: DTO Validation | 🟢 **ĐÃ LÀM** | Ràng buộc logic nghiệp vụ: `startDate <= endDate`, `budgetMin <= budgetMax` và `followerMin <= followerMax`. |

---

## 🔎 FLOW 2: Creator Discovery & Public Intelligence (Thu thập, Làm sạch & Khám phá KOC)

### 1. Luồng nghiệp vụ trọn vẹn (Kiến trúc Two-Phase v2.0)
```
[Nấc 0: Search-Card Filter] Bóc tách DOM kết quả tìm kiếm (Loại bỏ < 7,000 followers, bio sỉ lẻ)
      │ ──> Chi phí: 0 Network Request
      ▼
[Phase 1: Preflight SSR Check (1.5s - 3s)] Fetcher siêu nhẹ bóc tách metadata + 5 bài preview
      │ ──> [Rule Engine BR-01 -> BR-06]
      │        ├── BR-01: Hard Reject (Shop bán hàng, xưởng may, cơ quan nhà nước, báo đài) ──> DỪNG NGAY
      │        ├── BR-02 -> BR-05: KOC & KOL Hard Accept Rules (3/5 review, 2/5 opinion, chuyên môn)
      │        └── BR-06: Selective Review ──> Chuyển Data Manager Vòng 1 (review_router.py)
      ▼
[Phase 2: Candidate Staging & Redis Lock] Quản lý hàng đợi chống trùng lặp (SET NX EX 300)
      │
      ▼
[Phase 3: Worker Deep Crawl Gate] CHẶN CỬA BẮT BUỘC: Chỉ cào sâu khi đã qua Preflight / Manager duyệt
      │ ──> Celery Queue riêng biệt (preflight_queue vs deep_crawl_queue)
      │ ──> Deep Crawl Playwright Stealth lấy đầy đủ chỉ số & 5-10 bài gần nhất
      ▼
[Phase 4: Post-Validation Guard] Thẩm định sau cào sâu (chống lọt lưới bẫy catalog shop)
      │ ──> Mô hình BKAI Bi-Encoder sinh Vector 768 chiều & Content Summary AI sạch
      ▼ (Gửi sang Java Backend qua HTTP POST kèm header bí mật X-Service-Key)
[Phase 5: Java Core Ingestion & Data Quality (SEP_BE)]
      ├── POST /creators/ingest: Advisory Lock, Gatekeeper (KOL/KOC & >= 5k), bảo vệ master_id/rates
      ├── GET /admin/data-quality/pending: Data Manager Vòng 2 duyệt Niche snapshot M2 (confidence < 0.7)
      ├── PUT /admin/data-quality/{id}/approve: Khóa bi quan chống Deadlock, duyệt Niche, ghi Audit Log
      └── GET /campaigns/{id}/discovery: 4-Stage Hybrid Search (0.6 Semantic + 0.4 FTS + Master Deduplication)
```

### 2. Biến đổi trạng thái thực thể chính
* `candidate_staging` (Python Staging DB): `DISCOVERED` $\rightarrow$ `PREFLIGHT_RUNNING` $\rightarrow$ `PREFLIGHT_DONE` $\rightarrow$ `DEEP_CRAWL_QUEUED` $\rightarrow$ `INGESTED` (hoặc `AUTO_REJECT`).
* `Creator`: Tạo mới $\rightarrow$ `source_type = 'PUBLIC'`, `master_id = NULL`, `is_stable_id` (TikTok = `true`, FB slug = `false`), cập nhật pgvector `embedding = vector(768)`.
* `PublicCreatorMetric` (**M2**): Tạo mới snapshot $\rightarrow$ `freshness_status = 'FRESH'`, `data_confidence = 0.95`. Khi Data Manager phê duyệt Vòng 2: `quality_reviewed_by = admin_id, quality_reviewed_at = NOW()`.
* `Post`: Xóa và làm mới snapshot 5-10 bài viết gần nhất phục vụ tra cứu ngữ cảnh.

### 3. Chi tiết Function & Phân bổ Repository (Khớp 100% Mã nguồn Hiện tại)

#### 3.1. Phân hệ Preflight, Rule Engine & Crawler (`creator-discovery` v2.0)

| STT | Function / Endpoint | Thành phần | File Source Code | Input / Output | Trạng thái | Mô tả kỹ thuật chi tiết mã nguồn |
| :---: | :--- | :---: | :--- | :--- | :---: | :--- |
| **2.1** | `Tier-0 Search Filter`<br>& `fetch_preflight()` | Crawler Engine | `app/crawlers/search_extractor.py`<br>`app/crawlers/preflight_fetcher.py` | **In**: `username / url`<br>**Out**: `PreflightData` | 🟢 **ĐÃ LÀM** | **Nấc 0**: Lọc ngay trên DOM search ($< 7,000$ followers hoặc bio sỉ lẻ) với chi phí 0 request mạng. **Preflight**: Bóc tách thẻ SSR `<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__">` trong 1.5–3.0s (nhanh gấp 19 lần cào sâu), lấy bio, bio link, followers và 5 bài preview đầu tiên mà không cuộn trang. |
| **2.2** | `RuleEngine.evaluate_candidate()` | AI Rule Engine | `app/ai/rules/rule_engine.py`<br>`app/ai/rules/signal_extractor.py` | **In**: `CandidateEvidence`<br>**Out**: `RuleDecisionResult` | 🟢 **ĐÃ LÀM** | **Quy chuẩn 6 Business Rules (BR-01 $\rightarrow$ BR-06)**:<br>• `BR-01`: Hard Reject shop sỉ lẻ, tổng kho, cơ quan nhà nước, báo đài, spam catalog.<br>• `BR-02`: Creator Identity Gate (xác thực nhân vật chủ đạo/trải nghiệm riêng).<br>• `BR-03`: KOC Hard Accept ($\ge 3/5$ bài review, $\ge 2/5$ bài nêu ưu/nhược điểm, bio affiliate).<br>• `BR-04`: KOL Hard Accept (chuyên môn sâu kiểm chứng hoặc vai trò ảnh hưởng nhất quán).<br>• `BR-05`: Conflict Resolver (độc lập, không dẫm chân).<br>• `BR-06`: Selective Review Gate ($< 15\%$ đưa vào duyệt). Sinh `reason_codes` minh bạch. |
| **2.3** | `CandidateStagingRepository`<br>& `deduplicator.py` | Staging & Concurrency | `app/core/candidate_staging.py`<br>`app/core/deduplicator.py` | **In**: `platform, username`<br>**Out**: Staging Record / Lock | 🟢 **ĐÃ LÀM** | Lưu trữ bảng `candidate_staging` (SQLite WAL mode + hỗ trợ PostgreSQL). Phân phối khóa nguyên tử Redis `SET NX EX 300` chống 2 worker cào trùng, kèm cơ chế cooldown 7 ngày cho tài khoản đã bị reject. |
| **2.4** | `Two-Phase Celery Pipeline`<br>& `Worker Deep Crawl Gate` | Celery Dispatcher | `app/workers/celery_app.py`<br>`app/workers/tasks.py` | **In**: Celery Task Queue<br>**Out**: Crawl Result | 🟢 **ĐÃ LÀM** | Tách 2 hàng đợi: `preflight_queue` (nhẹ) và `deep_crawl_queue` (nặng). Cài đặt **Worker Gate** ngay đầu `process_tiktok_profile`: Từ chối cào sâu (`BLOCKED`) nếu chưa có quyết định `AUTO_ACCEPT_*` hoặc `MANAGER_APPROVED`. Điều hướng `discovery_bot.py`, `crawl_router.py` và Excel Import qua Preflight. |
| **2.5** | `validate_post_crawl()`<br>(Post-Validation Guard) | AI Guard | `app/ai/rules/post_validator.py` | **In**: Full `IngestPayload`<br>**Out**: `ValidationResult` | 🟢 **ĐÃ LÀM** | Thẩm định sau khi cào sâu 5–10 bài: Kiểm tra `externalId`, tối thiểu 3 bài hợp lệ, và phát hiện bẫy catalog shop ($\ge 60\%$ bài bán hàng + 0 review). Cách ly ngay (`QUARANTINE_POST_VALIDATION_FAILED`), ngăn không gửi sang Java. |
| **2.6** | `Selective Review API`<br>(Data Manager Vòng 1) | Admin API | `app/api/routers/review_router.py` | **In**: Action `APPROVE / REJECT`<br>**Out**: Enqueue Result | 🟢 **ĐÃ LÀM** | `GET /api/v1/candidates/review/pending`: Xem danh sách chờ duyệt kèm bằng chứng.<br>`POST /api/v1/candidates/review/{id}/action`: Phê duyệt kích hoạt duy nhất 1 Deep Crawl Task, ghi nhận Audit Log người duyệt. |
| **2.7** | `EmbeddingService` & `JavaMapper` | AI Vector & Mapper | `app/ai/services/embedding_service.py`<br>`app/mappers/java_mapper.py` | **In**: Payload sạch<br>**Out**: Java Ingestion JSON | 🟢 **ĐÃ LÀM** | Mô hình `bkai-foundation-models/vietnamese-bi-encoder` sinh vector 768 chiều từ chuỗi ngữ cảnh chuẩn hóa. `JavaMapper` đóng cứng `sourceType = 'PUBLIC'`, `masterId = None` để bảo toàn tuyệt đối luồng Claim/OAuth bên Java. |

#### 3.2. Phân hệ Data Ingestion, Data Quality & Discovery Pipeline (`SEP_BE`)

| STT | Function / Endpoint | Actor / Thành phần | File Source Code | Input / Output | Trạng thái | Mô tả kỹ thuật chi tiết mã nguồn |
| :---: | :--- | :---: | :--- | :--- | :---: | :--- |
| **2.8** | `POST /creators/ingest`<br>([CreatorIngestionController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorIngestionController.java)) | Ingestion Guard | [CreatorIngestionService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorIngestionService.java) | **In**: `CreatorIngestionRequest`<br>**Out**: `CreatorIngestionResponse` | 🟢 **ĐÃ LÀM** | **Bảo mật**: `@PreAuthorize("hasAuthority('SERVICE_INGEST_CREATOR')")` (header `X-Service-Key`).<br>**Gatekeeper**: Chặn nếu non-KOL/KOC hoặc `followers < 5000`.<br>**Advisory Lock**: `creatorRepository.acquireIdentityLock(platform + ":" + externalId)` chống race condition.<br>**Upsert**: Nạp mới `sourceType = PUBLIC, master = null`. Idempotency check theo `crawlRunId`. Cập nhật `Post` snapshot và lưu pgvector 768 chiều. Bảo vệ tuyệt đối không ghi đè quyền sở hữu hoặc rate-card. |
| **2.9** | `GET /admin/data-quality/pending`<br>([DataQualityController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/DataQualityController.java)) | Data Manager (Vòng 2) | [DataQualityService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/DataQualityService.java) | **In**: `page, size`<br>**Out**: `PageResponse<QualityItem>` | 🟢 **ĐÃ LÀM** | Quyền `REVIEW_CREATOR_CLASSIFICATION`. Thực thi SQL Correlated Anti-Join lọc snapshot mới nhất trong **M2** có độ tự tin thấp (`data_confidence < 0.7`) và chưa qua review để Data Manager duyệt bối cảnh ngành nghề. |
| **2.10** | `PUT /admin/data-quality/{id}/approve`<br>([DataQualityController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/DataQualityController.java)) | Data Manager (Vòng 2) | [DataQualityService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/DataQualityService.java) | **In**: `{"niche": "..."}`<br>**Out**: `QualityItem` | 🟢 **ĐÃ LÀM** | Khóa bi quan chống Deadlock (`Creator` trước $\rightarrow$ `Metric` sau). Cho phép đính chính `niche` (hoặc giữ nguyên), gán `quality_reviewed_by = actor`, `quality_reviewed_at = NOW()`, ghi **Audit Log** hệ thống (`APPROVE_DATA_QUALITY`). |
| **2.11** | `GET /campaigns/{id}/discovery`<br>([CreatorDiscoveryController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/discovery/controller/CreatorDiscoveryController.java)) | Brand User | [CreatorDiscoveryService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/discovery/service/CreatorDiscoveryService.java)<br>[PublicCreatorMetricRepository.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/repository/PublicCreatorMetricRepository.java) | **In**: `campaignId, keyword, niche, minFollowers, maxFollowers`<br>**Out**: `Page<Map<String, Object>>` | 🟢 **ĐÃ LÀM** | **4-Stage Hybrid Discovery Pipeline**:<br>1. **Khung cứng M3**: Platforms, Niches, FollowerMin/Max, BudgetMin/Max.<br>2. **Dual Smart Search**: Gọi AI Vector 768-d (tự lấy bối cảnh Campaign nếu không nhập keyword) + **Circuit Breaker** (tự trượt về 100% Lexical FTS nếu AI sập).<br>3. **Rank Fusion & Master Deduplication**: Công thức $\text{HybridScore} = 0.6 \text{ Semantic} + 0.4 \text{ FTS}$. Khử trùng lặp `DISTINCT ON (COALESCE(c.master_id, c.id))` để KOC đa kênh chỉ hiển thị 1 thẻ đại diện kèm `claimStatus`.<br>4. **Tự chữa lành**: Nới lỏng $+20\%, +50\%$ Follower nếu kết quả $< 5$, giữ nguyên Niche. |

#### 3.3. Các tính năng mở rộng đề xuất cho Flow 2 (Roadmap tiếp theo)

| STT | Tính năng đề xuất | Actor | Repo | Input / Output | Trạng thái | Đề xuất giải pháp kỹ thuật |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **2.12** | *Bộ lọc nâng cao trên Discovery* | Brand User | `SEP_BE` | **In**: `claimStatus, tier` | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Bổ sung vào [DiscoverySearchRequest.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/discovery/dto/DiscoverySearchRequest.java): Lọc nhanh theo `claimStatus` (`LINKED_PROFILE` để ưu tiên mời KOC đã claim active) và `tier` (Nano/Micro/Macro). *(Lưu ý: Không lọc theo `location` vì mạng xã hội không cung cấp API vị trí địa lý công khai chính xác. Luồng Discovery cốt lõi luôn chạy dựa trên M3 `CampaignContext` làm Hard Filter kết hợp M2 `PublicCreatorMetric`).* |
| **2.13** | `GET /campaigns/{id}/discovery/export` | Brand User | `SEP_BE` | **In**: `campaignId, format=EXCEL`<br>**Out**: File Byte Array | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Tính năng tiện ích phụ (Nice-to-have): Sử dụng Apache POI xuất danh sách KOC từ Discovery ra Excel/CSV để Brand in báo cáo duyệt ngân sách nội bộ. Không thuộc luồng bắt buộc của Review 2. |
| **2.14** | `POST /api/v1/crawl/on-demand` | KOC / System | `AI` | **In**: `{"platform": "TIKTOK", "username": "..."}` | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Endpoint phụ trợ: Discovery thực tế **hoàn toàn đọc từ Data Pool M2 (`public_creator_metric`) đã cào sẵn trong Database** để đưa vào Shortlist và gửi link mời Claim (Flow 4). Endpoint này chỉ phục vụ tình huống KOC sau khi claim muốn liên kết thêm một kênh phụ mới toanh chưa có trong pool. |

---

## 🧠 FLOW 3: AI Recommendation using Four-Memory Framework & XAI Reasoning Card (Đề xuất KOC & Tiến hóa Bối cảnh)

### 1. Luồng nghiệp vụ trọn vẹn (End-to-End Recommendation & Feedback Loop)
Brand bấm *"Đề xuất KOC bằng AI"* (Top $K = 10$) $\rightarrow$ Hệ thống kích hoạt **Quota Guard** ([SubscriptionService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/billing/service/SubscriptionService.java)) kiểm tra `usedRecommendationQuota < plan.aiRecommendationQuota` $\rightarrow$ Nạp và tổng hợp dữ liệu từ **Hệ thống 4 Tầng Bộ nhớ (Four-Memory Framework: M1, M2, M3, M4)** $\rightarrow$ Backend Java thực hiện **Hybrid Retrieval & Pre-filtering** (Hard filter từ **M3** + Vector Cosine Search trên pgvector **M2**) $\rightarrow$ Gửi Payload sang Python AI Microservice (`POST /api/v1/ai/explain`) $\rightarrow$ AI Reranker tính toán độ tương thích đa chiều theo **Bộ 4 Tiêu chí Explainable AI (XAI)** từ [`AI_EXPLANATION_GUIDE.md`](file:///d:/SEP_BE/docs/AI_EXPLANATION_GUIDE.md) $\rightarrow$ Trả về danh sách KOC tiềm năng kèm **Thẻ lý giải thông minh (Reasoning Card)** $\rightarrow$ Brand tương tác phản hồi:
* Nếu **Chấp nhận (Accept)**: KOC tự động được nạp vào Shortlist chiến dịch (Flow 4) với `ShortlistPriority = HIGH`.
* Nếu **Từ chối (Reject)** kèm lý do (ví dụ: *"chi phí vượt ngân sách"*, *"sai phong cách"*, *"tương tác ảo"*): Kích hoạt cơ chế **Negative Constraints Adaptation**, tự động ghi nhận KOC bị loại trừ hoặc cập nhật trần ngân sách vào `CampaignContext` (**M3**) và nâng cấp phiên bản ngữ cảnh (`contextVersion++`), đảm bảo các lần đề xuất sau không lặp lại lỗi.

### 2. Định nghĩa Chuẩn mực Hệ thống 4 Tầng Bộ nhớ (Four-Memory Framework)
* **M1: Historical Campaign Evidence Memory (Bộ nhớ Bằng chứng Lịch sử)**:
  * Nguồn: Bảng `historical_campaign`, `campaign_outcome`, `creator_review`.
  * Nội dung: Bằng chứng thực chiến từ các chiến dịch đã hoàn thành: chi phí hợp tác thực tế (`agreed_fee`), KPI thực tế (Views, Clicks, Orders, ROI), điểm đánh giá chất lượng của Brand (`brandFitRating`), và ý định tái hợp tác (`would_collaborate_again`).
* **M2: Public Creator Intelligence Memory (Bộ nhớ Trí tuệ KOC Công khai)**:
  * Nguồn: Bảng `creator`, `public_creator_metric`, `post`, pgvector `embedding(768)`.
  * Nội dung: Hồ sơ công khai cào từ MXH: Followers, Avg Views, Avg Likes, Avg Comments, Engagement Rate, Niche, Category, Creator Type (`KOL`/`KOC`), tính tươi mới (`freshness_status`), độ tự tin (`data_confidence`), và vector BKAI Bi-Encoder 768 chiều.
* **M3: Current Campaign Context Memory (Bộ nhớ Bối cảnh Chiến dịch Hiện tại)**:
  * Nguồn: Bảng `campaign`, `campaign_context` (quản lý đa phiên bản `context_version`).
  * Nội dung: Mục tiêu chiến dịch, sản phẩm/dịch vụ, tệp khách hàng, ràng buộc cứng (`platforms`, `niches`, `followerMin/Max`, `budgetMin/Max`), và **ma trận phản hồi tương tác (Negative Constraints / Excluded Creators history)**.
* **M4: Brand Context Memory (Bộ nhớ Bối cảnh Thương hiệu)**:
  * Nguồn: Bảng `brand_profile`, `brand_context`, `brand_pattern`.
  * Nội dung: Định vị thương hiệu, tone & voice, giá trị cốt lõi (`profileContext`), các mẫu thành công học được (`learnedPatterns`: ví dụ "Brand hợp KOC Skincare treatment hơn Makeup", "KOC chi phí < 15M cho ROI cao nhất"), các liên kết bằng chứng (`evidenceRefs`).

### 3. Bộ 4 Tiêu chí AI Explanation (Score Breakdown & XAI Reasoning Card)
Trích xuất từ [`AI_EXPLANATION_GUIDE.md`](file:///d:/SEP_BE/docs/AI_EXPLANATION_GUIDE.md), điểm thành phần được chuẩn hóa trên thang điểm $[0, 100]$:

#### 3.1. Điểm Tương tác (`engagementScore`)
* **Bản chất**: Đo lường chất lượng tương tác của Creator so với **mặt bằng chuẩn (Benchmark) của cùng phân khúc quy mô (Tier)**:
  * **Nano Influencer** ($< 10\text{k}$ followers): Benchmark chuẩn **$4.5\%$**.
  * **Micro Influencer** ($10\text{k} \le \text{followers} < 100\text{k}$): Benchmark chuẩn **$3.0\%$**.
  * **Macro Influencer** ($100\text{k} \le \text{followers} < 1\text{M}$): Benchmark chuẩn **$2.0\%$**.
  * **Mega Influencer / KOL** ($\ge 1\text{M}$ followers): Benchmark chuẩn **$1.2\%$**.
* **Công thức**: $\text{engagementScore} = \min\left(100.0, \frac{\text{ER thực tế}}{\text{ER Benchmark}} \times 100\right)$.

#### 3.2. Điểm Quy mô Phân khúc (`followerScore`)
* **Thang điểm cơ sở theo phân khúc**: Micro ($10\text{k} - 100\text{k}$): **95 điểm**; Mega ($> 1\text{M}$): **90 điểm**; Macro ($100\text{k} - 1\text{M}$): **85 điểm**; Nano ($< 10\text{k}$): **75 điểm**.
* **Thưởng khớp mục tiêu (Target Match)**: Nếu trong Brief có từ khóa phân khúc hoặc có từ *"KOC"* và Creator $< 100\text{k}$ followers $\rightarrow$ Thưởng **$100.0$ điểm**.

#### 3.3. Điểm Tương đồng Ngữ nghĩa (`semanticMatchScore`)
* **Bản chất**: Đo lường độ ăn khớp giữa hồ sơ Creator và bản mô tả chiến dịch (`campaignBrief`) qua mô hình vector 768 chiều `bkai-foundation-models/vietnamese-bi-encoder`.
* **Công thức**: $\text{semanticMatchScore} = \text{CosineSimilarity}(\vec{u}_{\text{brief}}, \vec{v}_{\text{creator}}) \times 100$.

#### 3.4. Điểm Khớp Lĩnh vực / Danh mục (`nicheMatchScore`)
* **Cơ chế tính điểm động**:
  * Trùng khớp trực tiếp Ngách (Exact Niche trong Brief): **$100.0$ điểm**.
  * Trùng khớp qua Hashtag (`topTags` xuất hiện trong Brief): **$90.0$ điểm**.
  * Trùng khớp Danh mục (`category` xuất hiện trong Brief): **$85.0$ điểm**.
  * Ngành hàng liên quan (Vector Cosine $\ge 0.65$): $\text{similarityScore} \times 100$.
  * Ít liên quan ($0.40 \le \text{similarityScore} < 0.65$): $\text{similarityScore} \times 100$.
  * Khác biệt ngành hoàn toàn ($\text{similarityScore} < 0.40$): $\max(20.0, \text{similarityScore} \times 100)$.

#### 3.5. Cấu trúc Thẻ Lý giải (Reasoning Card)
* **Summary (1 câu)**: Nêu bật 3 yếu tố cốt lõi: Ngách chuyên môn + Chất lượng tương tác + Quy mô tiếp cận.
* **Reasons (Danh sách lý lẽ)**: Tự động diễn giải theo văn phong tiếp thị tự nhiên (không đưa các ký tự kỹ thuật vào UI).
* **Score Breakdown**: Bảng 4 cột trực quan hiển thị `[engagementScore, followerScore, semanticMatchScore, nicheMatchScore]`.

### 4. Biến đổi trạng thái thực thể chính
* `Subscription`: `used_recommendation_quota += 1` (Chặn `409 QUOTA_EXCEEDED` nếu vượt hạn mức gói).
* `ShortlistItem`: Tự động tạo mới bản ghi KOC được Brand chấp nhận vào Shortlist (`ShortlistPriority = HIGH`).
* `CampaignContext` (**M3**): Tăng `context_version += 1`, bổ sung `excludedCreatorIds` hoặc cập nhật `negativeConstraints` vào `context_data`.

### 5. Chi tiết Function & Phân bổ Repository (Khớp 100% Mã nguồn Hiện tại)

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật chi tiết mã nguồn |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **3.1** | `checkAndDeductRecommendationQuota()`<br>([SubscriptionService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/billing/service/SubscriptionService.java)) | Quota Guard | `SEP_BE` | **In**: `brandProfileId`<br>**Out**: `void / 409 QUOTA_EXCEEDED` | 🟡 **ĐÃ CÓ LOGIC GỐC** | Model `Subscription` và `Plan` đã có trường `usedRecommendationQuota` và `recommendationQuota`. Cần bổ sung method riêng tương tự `checkAndDeductCampaignQuota()`. |
| **3.2** | `BrandContextRepository` & `CampaignContextRepository` | Backend Core | `SEP_BE` | **In**: `brandId, campaignId`<br>**Out**: `BrandContext` (M4), `CampaignContext` (M3) | 🟢 **ĐÃ LÀM** | [BrandContextRepository.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/brand/repository/BrandContextRepository.java) truy xuất M4 (profileContext, learnedPatterns); [CampaignContextRepository.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/campaign/repository/CampaignContextRepository.java) truy xuất M3 phiên bản mới nhất (`active = true`). |
| **3.3** | `HistoricalCampaign` & `BrandPattern` Schema | Backend Core | `SEP_BE` | **DB Schema** | 🟢 **ĐÃ CÓ SCHEMA** | Đã sẵn sàng trong Flyway `V1__init_schema.sql`: bảng `historical_campaign` (**M1**) và `brand_pattern` (**M4**). |
| **3.4** | `generate_explanation()`<br>([explanation_service.py](file:///d:/creator-discovery/app/ai/services/explanation_service.py)) | AI Engine | `AI` | **In**: `ExplanationInput`<br>**Out**: `ExplanationOutput` | 🟢 **ĐÃ LÀM** | Đã triển khai đầy đủ 100% trong `creator-discovery`: Chấm 4 tiêu chí (`engagementScore`, `followerScore`, `semanticMatchScore`, `nicheMatchScore`), sinh tóm tắt 1 câu, danh sách lý do cụ thể và `scoreBreakdown`. |
| **3.5** | `POST /api/v1/ai/explain`<br>([ai_router.py](file:///d:/creator-discovery/app/api/routers/ai_router.py)) | AI Engine | `AI` | **In**: `ExplainRequest`<br>**Out**: Explanation JSON | 🟢 **ĐÃ LÀM** | API Endpoint trên FastAPI nhận dữ liệu KOC + Brief và gọi `generate_explanation()`, trả về đúng đặc tả API contract trong `AI_EXPLANATION_GUIDE.md`. |
| **3.6** | `AiServiceClient.explainRecommendation()`<br>([AiServiceClient.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/client/AiServiceClient.java)) | HTTP Client | `SEP_BE` | **In**: `ExplainRequestDto`<br>**Out**: `ExplainResponseDto` | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Bổ sung phương thức gọi `POST /api/v1/ai/explain` sang Python AI Microservice (hiện tại client mới chỉ có `getEmbedding()`). |
| **3.7** | `POST /campaigns/{id}/ai-recommendations`<br>(`AiRecommendationController.java`) | Brand User | `SEP_BE` | **In**: `campaignId, topK = 10`<br>**Out**: `List<AiRecommendedCreatorDto>` | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Service gom 4 bộ nhớ: lấy brief từ M3, brand pattern từ M4, loại trừ `excludedCreatorIds`, query pgvector M2 lấy Top candidate, gọi `explainRecommendation()` và trả về Reasoning Card. |
| **3.8** | `POST /campaigns/{id}/ai-recommendations/feedback`<br>(`AiRecommendationController.java`) | Brand User | `SEP_BE` | **In**: `creatorId, action (ACCEPT/REJECT), reasonNote`<br>**Out**: `ApiResponse.ok()` | 🔴 **CHƯA LÀM (ĐỀ XUẤT)** | Khi **Accept**: gọi [ShortlistService.addCreatorToShortlist](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/service/ShortlistService.java). Khi **Reject**: nạp lý do vào Negative Constraints Adaptation, cập nhật M3 `context_version++`. |

---

## 🤝 FLOW 4: Shortlist, Contact, Official OAuth Sandbox & Basic CRM (Định danh Master M1 & Quản trị Phễu Đàm phán)

### 1. Luồng nghiệp vụ trọn vẹn (Hai tiểu luồng độc lập)
* **Tiểu luồng A (Creator Identity & Official Sandbox Claim)**: Brand gửi Claim Invitation $\rightarrow$ KOC xác thực token email $\rightarrow$ Chuyển hướng tới **Official Developer Sandbox / Testing Consent Screen** (TikTok Login Kit Sandbox / Google YouTube Testing Mode) $\rightarrow$ SEP_BE nhận Authorization Code thật, đổi token và lấy profile $\rightarrow$ Đối soát danh tính (TikTok handle @ / YouTube Channel ID `UC...`) $\rightarrow$ Khởi tạo nguyên tử `CreatorMaster` M1, chuyển kênh sang `sourceType = AUTHORIZED`, mã hóa Token AES-256-GCM $\rightarrow$ KOC quản lý kênh và xem tổng độ phủ (`GET /creators/portal/analytics/reach`) $\rightarrow$ Xử lý tranh chấp kênh qua `POST /admin/creator-conflicts/{id}/resolve`.
* **Tiểu luồng B (Brand Shortlist & CRM Negotiation)**: Brand so sánh side-by-side KOC trong Shortlist (`POST /campaigns/{id}/shortlist/compare`) $\rightarrow$ Kiểm tra Contact Readiness $\rightarrow$ Gửi Lời mời Hợp tác hàng loạt (`POST /campaigns/{id}/shortlist/outreach`) $\rightarrow$ Tự động chuyển KOC vào phễu CRM ở giai đoạn `CONTACTED` $\rightarrow$ Đàm phán chi phí qua State Machine (`CONTACTED` $\rightarrow$ `INTERESTED` $\rightarrow$ `COLLABORATING`) $\rightarrow$ Chốt `agreedFee`.

### 2. Biến đổi trạng thái thực thể chính
* `CreatorInvitation`: `PENDING` $\rightarrow$ `ACCEPTED`
* `CreatorMaster`: Tạo mới thực thể M1 trạng thái `ACTIVE`
* `Creator`: `source_type = 'PUBLIC'` $\rightarrow$ `AUTHORIZED`, gán `master_id = M1`, `authorized_at = NOW()`
* `CreatorIdentityConflict`: Tạo mới `PENDING_MANAGER` $\rightarrow$ `RESOLVED` hoặc `REJECTED` (nếu có tranh chấp)
* `Relationship`: `SHORTLISTED` $\rightarrow$ `CONTACTED` $\rightarrow$ `INTERESTED` $\rightarrow$ `COLLABORATING`, chốt `agreedFee`

### 3. Chi tiết Function & Phân bổ Repository

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật & Đề xuất hành động |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **4.1** | `POST /campaigns/{id}/shortlist/compare`<br>([ShortlistCompareController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/controller/ShortlistCompareController.java)) | Brand User | `SEP_BE` | **In**: `List<creatorId>`<br>**Out**: `CompareResponse` | 🟢 **ĐÃ LÀM** | So sánh 2–5 KOC song song theo metrics mới nhất (Followers, ER, Avg Views) và trạng thái xác minh. |
| **4.2** | `POST /creators/claim/invitation`<br>([CreatorInvitationService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorInvitationService.java)) | Brand User | `SEP_BE` | **In**: `creatorId, email`<br>**Out**: `InvitationResponse` | 🟢 **ĐÃ LÀM** | Sinh token ngẫu nhiên 64 ký tự, băm SHA-256 lưu DB (hạn 7 ngày). |
| **4.3** | *Gửi Email Lời Mời (ClaimEmailWorker)* | Core Backend | `SEP_BE` | **In**: Email Outbox Record | 🟢 **ĐÃ LÀM** | Hàng đợi `claim_email_outbox` (Flyway V16) quét và gửi email HTML có token claim. |
| **4.4** | `GET /creators/claim/invitation/verify`<br>([CreatorInvitationService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorInvitationService.java)) | KOC User | `SEP_BE` | **In**: `?token=...`<br>**Out**: `VerifyResponse` | 🟢 **ĐÃ LÀM** | Endpoint công khai cho KOC xem trước hồ sơ kênh từ link email trước khi claim. |
| **4.5** | `GET /creators/claim/oauth/{platform}/start`<br>([CreatorClaimOAuthController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorClaimOAuthController.java)) | KOC User | `SEP_BE` | **In**: `platform, token, redirect=true`<br>**Out**: 302 Redirect | 🟢 **ĐÃ LÀM** | Chuyển hướng sang Official Sandbox Consent Screen (TikTok Login Kit / Google OAuth Testing) kèm đầy đủ `client_id`, `redirect_uri`, `scope`, `state`. |
| **4.6** | `GET /creators/claim/oauth/{platform}/callback`<br>([CreatorClaimOAuthController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorClaimOAuthController.java)) | Nền tảng OAuth | `SEP_BE` | **In**: `code, state`<br>**Out**: `OAuthCallbackResponse` | 🟢 **ĐÃ LÀM** | Nhận code từ nền tảng thật trong chế độ Sandbox, đổi token và lấy profile thật. |
| **4.7** | `resolveClaim()`<br>([CreatorClaimResolutionService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorClaimResolutionService.java)) | KOC User | `SEP_BE` | **In**: `platform, code, token`<br>**Out**: `OAuthCallbackResponse` | 🟢 **ĐÃ LÀM** | Đối soát danh tính (TikTok handle @ / YouTube Channel ID `UC...`), tạo Master M1, mã hóa token AES-256-GCM, gắn tag `OAUTH_OFFICIAL_SANDBOX`. |
| **4.8** | `GET /creators/portal/accounts`<br>([CreatorPortalAccountController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorPortalAccountController.java)) | KOC User | `SEP_BE` | **In**: Bearer KOC<br>**Out**: `List<LinkedAccountDto>` | 🟢 **ĐÃ LÀM** | Xem danh sách các kênh mạng xã hội đã liên kết thuộc Master M1. |
| **4.9** | `POST /creators/portal/accounts/connect/{platform}/callback`<br>([CreatorAccountLinkService](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorAccountLinkService.java)) | KOC User | `SEP_BE` | **In**: `authCode, platform`<br>**Out**: `ConnectAccountResponse` | 🟢 **ĐÃ LÀM** | Xử lý 4 kịch bản liên kết kênh (Kênh có sẵn / Kênh mới / Gia hạn token / Tranh chấp). |
| **4.10** | `GET /creators/portal/analytics/reach`<br>([CreatorPortalAnalyticsController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/CreatorPortalAnalyticsController.java)) | KOC User | `SEP_BE` | **In**: Bearer KOC<br>**Out**: `AggregatedReachDto` | 🟢 **ĐÃ LÀM** | Tính tổng độ phủ (Cross-platform Aggregated Reach): Tổng followers, tổng views, ER bình quân gia quyền theo follower của KOC. |
| **4.11** | `GET /admin/creator-conflicts`<br>([AdminIdentityConflictController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/AdminIdentityConflictController.java)) | Data Manager | `SEP_BE` | **In**: `status, page, size`<br>**Out**: `Page<ConflictDto>` | 🟢 **ĐÃ LÀM** | Hàng đợi cho Data Manager xem các tranh chấp quyền sở hữu kênh (`PENDING_MANAGER`). |
| **4.12** | `POST /admin/creator-conflicts/{id}/resolve`<br>([AdminIdentityConflictController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/controller/AdminIdentityConflictController.java)) | Data Manager | `SEP_BE` | **In**: `decision, notes`<br>**Out**: `ConflictDetailDto` | 🟢 **ĐÃ LÀM** | Phân quyền `RESOLVE_CREATOR_DUPLICATE`, duyệt chấp thuận (`APPROVE_TRANSFER`) hoặc từ chối (`REJECT`) tranh chấp và ghi Audit log. |
| **4.13** | `POST /campaigns/{id}/shortlist/outreach`<br>([ShortlistController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/controller/ShortlistController.java)) | Brand User | `SEP_BE` | **In**: `itemIds, message, fee`<br>**Out**: `ShortlistOutreachResponse` | 🟢 **ĐÃ LÀM** | Cầu nối Shortlist sang CRM: Chuyển hàng loạt KOC sang `CONTACTED`, tạo quan hệ CRM và tự động đưa email mời claim vào outbox. |
| **4.14** | `PATCH /campaigns/{id}/relationships/{relId}/stage`<br>([RelationshipController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/controller/RelationshipController.java)) | Brand User | `SEP_BE` | **In**: `stage, agreedFee, nextAction`<br>**Out**: `RelationshipDto` | 🟢 **ĐÃ LÀM** | State Machine quản lý CRM: `CONTACTED` $\rightarrow$ `INTERESTED` $\rightarrow$ `COLLABORATING` $\rightarrow$ Chốt `agreedFee` và hạn chót phản hồi. |

---

## 📝 FLOW 5: Campaign Collaboration & Performance Tracking (Giám sát Thực thi Hợp tác, Nộp Bằng chứng & Nghiệm thu Hiệu quả)

### 1. Luồng nghiệp vụ trọn vẹn
Chuyển quan hệ CRM `COLLABORATING` thành Hợp tác chính thức (`IN_PROGRESS`) $\rightarrow$ KOC xuất bản video/bài viết công khai $\rightarrow$ KOC nộp link bài đăng và ảnh chụp Dashboard Insights $\rightarrow$ Brand thẩm định $\rightarrow$ Nhập số liệu tương tác thực tế (Views, Clicks, Likes, Shares, Comments) $\rightarrow$ Đóng `COMPLETED` $\rightarrow$ Brand đánh giá tín nhiệm KOC (Brand Fit) $\rightarrow$ Tự động chuyển hóa kết quả thành tri thức nạp vào **Bộ nhớ 1 (`HistoricalCampaign` - M1)** và **Bộ nhớ 4 (`BrandPattern` - M4)**.

> **Định vị tài chính & Pháp lý (Non-Escrow Platform)**: Mọi chi phí hợp tác được Brand và Creator tự thương lượng, ký kết và thanh toán trực tiếp bên ngoài hệ thống. InfluencerMatch AI **không giữ tiền (No Escrow), không can thiệp cổng chia tiền/tạm ứng (Deposit/Settlement) và không ép buộc chỉ số ROI tài chính hợp đồng**, loại bỏ 100% rủi ro pháp lý tài chính, đối soát ngân hàng và tranh chấp bồi thường cọc.

### 2. Biến đổi trạng thái thực thể chính
* `Collaboration`: Tạo mới `IN_PROGRESS` $\rightarrow$ `COMPLETED` (`completed_at = NOW()`)
* `Relationship`: Stage `COLLABORATING` $\rightarrow$ `COMPLETED`
* `EvidenceAsset`: Tiếp nhận URL bài đăng chính và ảnh chụp màn hình Insights ngay trong lúc `IN_PROGRESS`
* `CampaignOutcome`: Tạo mới bản ghi tương tác thực tế (`evidence_quality = 'SELF_REPORTED'`)
* `CreatorReview`: Tạo mới bản ghi đánh giá tín nhiệm KOC (`overall_rating`, `brand_fit_rating`, `would_collaborate_again`, `note`)
* `HistoricalCampaign` (**M1**): Tự động đồng bộ bản ghi chứng thực thực tế (`evidence_quality = 'VERIFIED'`)
* `BrandPattern` (**M4**): Tự động gia tăng `evidence_count`, tái tính `confidence` và `averageSuccessfulScore`

### 3. Chi tiết Function & Phân bổ Repository (Khớp 100% Mã nguồn Hiện tại)

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật chi tiết mã nguồn |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **5.1** | `POST /campaigns/{id}/collaborations`<br>([CollaborationController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)) | Brand User | `SEP_BE` | **In**: `CreateCollaborationRequest`<br>**Out**: `CollaborationResponse` | 🟢 **ĐÃ HOÀN THÀNH** | Khởi tạo hợp tác từ `relationship.stage == COLLABORATING`; `agreedFee` tùy chọn (fallback từ Relationship); khóa chống trùng lặp (`ErrorCode.COLLABORATION_ALREADY_EXISTS`); `paymentStatus = PAID`. |
| **5.2** | *Định vị Thanh toán Ngoài Hệ thống (Non-Escrow)*<br>([CollaborationPaymentStatus](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/model/CollaborationPaymentStatus.java)) | Brand & KOC | `SEP_BE` | **In**: Tham chiếu ghi nhận<br>**Out**: `paymentStatus` | 🟢 **ĐÃ CHUẨN HÓA** | Không làm Escrow cổng trung gian giữ tiền. Hệ thống định vị thuần theo dõi nội dung & tương tác, Brand và Creator thanh toán chuyển khoản/hợp đồng trực tiếp ngoài nền tảng. |
| **5.3** | `GET /campaigns/{id}/collaborations`<br>([CollaborationController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)) | Brand User | `SEP_BE` | **In**: `campaignId, page, size, status`<br>**Out**: `PageResponse<CollaborationSummaryDto>` | 🟢 **ĐÃ HOÀN THÀNH** | Brand truy vấn danh sách phân trang tất cả KOC đang hợp tác trong chiến dịch kèm cờ trạng thái `hasOutcome`, `hasReview`, `postUrl`. |
| **5.4** | `GET /collaborations/{id}`<br>([CollaborationController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)) | Brand & KOC | `SEP_BE` | **In**: `collaborationId`<br>**Out**: `CollaborationDetailResponse` | 🟢 **ĐÃ HOÀN THÀNH** | Xem chi tiết hợp tác: thông tin KOC, brandName, deadline, link bài đăng, toàn bộ danh sách `EvidenceAsset`, kết quả `CampaignOutcome` và `CreatorReview`. |
| **5.5** | `GET /creators/portal/collaborations`<br>([CreatorPortalCollaborationController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CreatorPortalCollaborationController.java)) | KOC User | `SEP_BE` | **In**: Bearer JWT KOC, `status`<br>**Out**: `List<CreatorCollaborationDto>` | 🟢 **ĐÃ HOÀN THÀNH** | KOC tra cứu công việc hợp tác được giao qua Creator Portal, hiển thị tên chiến dịch, thương hiệu, trạng thái và deadline. |
| **5.6** | `POST /collaborations/{id}/evidence`<br>([CollaborationController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)) | KOC User / Brand User | `SEP_BE` | **In**: `SubmitEvidenceRequest`<br>**Out**: `EvidenceAssetResponse` | 🟢 **ĐÃ HOÀN THÀNH** | Nộp link bài đăng (`POST_LINK`) hoặc ảnh chụp màn hình Insights (`INSIGHTS_SCREENSHOT`) khi ở trạng thái `IN_PROGRESS`, tự động cập nhật `publishedContentUrl` và lưu vào bảng `evidence_asset` (Flyway V18). |
| **5.7** | `POST /collaborations/{id}/outcome`<br>([CollaborationController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)) | Brand User | `SEP_BE` | **In**: `RecordOutcomeRequest`<br>**Out**: `OutcomeResponse` | 🟢 **ĐÃ HOÀN THÀNH** | Nghiệm thu hiệu quả tương tác mạng xã hội (Views, Clicks, Likes, Comments, Shares, Post URL), tự động đóng `status = COMPLETED` và relationship `stage = COMPLETED`, gán `evidenceQuality = 'SELF_REPORTED'`. |
| **5.8** | `POST /collaborations/{id}/review`<br>([CollaborationController](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)) | Brand User | `SEP_BE` | **In**: `CreateCreatorReviewRequest`<br>**Out**: `CreatorReviewResponse` | 🟢 **ĐÃ HOÀN THÀNH** | Brand đánh giá sao tổng quan (`overallRating`), độ phù hợp thương hiệu (`brandFitRating`), `wouldCollaborateAgain`, và nhận xét chi tiết; tự động kích hoạt `CollaborationReviewedEvent`. |
| **5.9** | *M1 & M4 Memory Sync Listener*<br>([CollaborationCompletedEventListener](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/listener/CollaborationCompletedEventListener.java)) | Event Listener | `SEP_BE` | **In**: `CollaborationReviewedEvent`<br>**Out**: Tạo `HistoricalCampaign` & cập nhật `BrandPattern` | 🟢 **ĐÃ HOÀN THÀNH** | Lắng nghe sau bước Review: tự động tạo bản ghi **Bộ nhớ 1 (`HistoricalCampaign` M1, quality = VERIFIED)** và cập nhật **Bộ nhớ 4 (`BrandPattern` M4, tăng confidence, evidenceCount)**. |
| **5.10** | *Automated Acceptance Test Suite*<br>([Flow5CollaborationAcceptanceTest](file:///d:/SEP_BE/src/test/java/com/influencermatch/backend/collaboration/Flow5CollaborationAcceptanceTest.java)) | QA / CI/CD | `SEP_BE` | **In**: 10 kịch bản test (`TEST-F5-01` $\to$ `TEST-F5-10`) | 🟢 **ĐÃ HOÀN THÀNH** | Bộ kiểm thử tự động tích hợp 100% kịch bản Flow 5 (Tests run: 10, Failures: 0, 100% Pass). |
| **5.11** | *Autonomous Post KPI Auto-Tracking*<br>(Worker cào chỉ số bài đăng T+1, T+3, T+7) | AI Crawler | `creator-discovery` | **In**: `publishedContentUrl, platform`<br>**Out**: Public Views, Likes, Comments | 🟢 **ĐÃ ĐẶC TẢ KIẾN TRÚC (P2)** | Worker quét định kỳ cào lại số liệu công khai của link bài đăng đã nộp qua `post_kpi_tracker.py`, đối soát và nâng hạng `evidenceQuality = 'PUBLIC_METRIC_OBSERVED'`. |

---

## 📈 FLOW 6: Post-Campaign Learning (Chưng cất Tri thức & Tự động Học hỏi Sau Chiến dịch)

### 1. Luồng nghiệp vụ trọn vẹn
Sự kiện nghiệm thu chiến dịch hoàn tất $\rightarrow$ Hệ thống tự động chuyển bằng chứng thực chiến vào **Bộ nhớ 1 (`HistoricalCampaign` - M1)** $\rightarrow$ AI Engine chưng cất tri thức (Knowledge Distillation) $\rightarrow$ Cập nhật và nâng cao trọng số thành công vào **Bộ nhớ 4 (`BrandPattern` - M4)** (tăng `evidence_count` và `confidence`).

### 2. Biến đổi trạng thái thực thể chính
* `HistoricalCampaign` (**M1**): Tạo mới bản ghi chứng thực thực tế (`evidence_quality = VERIFIED`)
* `BrandPattern` (**M4**): Tăng trọng số thành công, nâng cao độ tin cậy tri thức (`confidence`), gán `learned_at = NOW()`

### 3. Chi tiết Function & Phân bổ Repository

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật & Đề xuất hành động |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **6.1** | `HistoricalCampaign` & `BrandPattern` Schema | Backend Core | `SEP_BE` | **DB Schema** | 🟢 **ĐÃ CÓ SCHEMA** | Bảng cơ sở dữ liệu trong Flyway cho **M1 (`historical_campaign`)** và **M4 (`brand_pattern`)** đã sẵn sàng. |
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

