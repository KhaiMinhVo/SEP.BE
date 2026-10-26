# 📝 FLOW 5: Campaign Collaboration & Performance Tracking
> **Phân hệ**: Giám sát Thực thi Hợp tác, Nộp Bằng chứng Bài đăng & Nghiệm thu Hiệu quả Chiến dịch (Phi Tài chính).  
> **Dự án**: InfluencerMatch AI (Capstone Project - SEP490)  
> **Phạm vi mã nguồn**: `D:\SEP_BE` (Core Backend) & `D:\creator-discovery` (Autonomous Post Tracking).  
> **Định vị nghiệp vụ**: InfluencerMatch AI là nền tảng tìm kiếm, đề xuất và giám sát hiệu quả KOC/KOL — **không phải nền tảng trung gian thanh toán hay quản lý tài chính hợp đồng**. Mọi chi phí hợp tác được Brand và Creator tự đàm phán và thanh toán trực tiếp bên ngoài hệ thống.

---

## 📌 I. NGUYÊN TẮC KIẾN TRÚC & PHẠM VI NGHIỆP VỤ (BUSINESS SCOPE)

```
                            CHU TRÌNH FLOW 5: COLLABORATION & TRACKING
                                                │
    ┌───────────────────────────────────────────┼───────────────────────────────────────────┐
    ▼                                           ▼                                           ▼
【1. CONFIRM COLLABORATION】         【2. DELIVERABLES & EVIDENCE】            【3. OUTCOME & AI LEARNING】
(Khởi tạo Hợp tác Chính thức)        (Thực thi, Nộp Link & Bằng chứng)         (Nghiệm thu KPI & Nạp vào AI)
• Chuyển từ Phễu CRM (Flow 4)        • KOC xuất bản video/bài viết công khai   • Ghi nhận Views, Clicks, Likes
• Stage COLLABORATING -> IN_PROGRESS • KOC nộp link bài đăng & ảnh Insights    • Đánh giá sao & Brand Fit (1-5)
• Không yêu cầu cọc/thanh toán       • Brand theo dõi qua Dashboard List/Detail• Tự động nạp vào M1 Historical Memory
• Phí agreedFee chỉ để tham khảo     • Crawler hỗ trợ đối soát chỉ số T+3, T+7 • Cập nhật M4 Brand Learning Pattern
```

### 1. Phân định ranh giới tài chính (Financial Boundary)
* **Không làm Escrow / Payment Gateway hợp đồng**: Nền tảng không tham gia thu/chi tiền hợp đồng của Creator, không quản lý ký quỹ, không chia đợt tạm ứng (Deposit 50%) hay quyết toán (Final Settlement 50%).
* **Bảo toàn Billing Subscription (Flow 7)**: Gói cước dịch vụ Brand trả cho nền tảng InfluencerMatch qua VNPay là nghiệp vụ hoàn toàn độc lập, không bị ảnh hưởng.
* **agreedFee mang tính chất Metadata**: Trường chi phí chỉ phục vụ lưu trữ ngân sách ước tính nội bộ của Brand, không tạo hóa đơn hay giao dịch trên hệ thống. Trạng thái `paymentStatus` không hiển thị `PENDING` cho Brand.

### 2. Giá trị cốt lõi cho Four-Memory AI (Học từ kết quả phi tài chính)
Dù không theo dõi dòng tiền hay tính ROI tài chính, hệ thống vẫn thu thập đầy đủ tín hiệu thực chiến chất lượng cao:
* **M1 (Historical Collaboration Memory)**:
  * *Tỷ lệ hoàn thành (Completion Rate)*: KOC có hoàn tất đầy đủ cam kết bàn giao không.
  * *Mức độ đúng hạn (On-time Delivery)*: Nộp bài đúng hạn hay trễ hạn.
  * *Hiệu quả tương tác thực tế (Observed Engagement)*: Số lượt xem (Views), lượt tương tác (Likes, Comments, Shares) được chứng thực.
  * *Mức độ phù hợp thương hiệu (Brand Fit Rating)*: Điểm số từ 1–5 do Brand trực tiếp đánh giá.
  * *Xu hướng tái hợp tác (Would Collaborate Again)*: Tín hiệu then chốt để cộng điểm ưu tiên trong Flow 3.
* **M4 (Brand Learning Pattern)**:
  * Tích lũy các mô thức KOC mà Brand thường xuyên đánh giá cao theo từng Niche và Platform.

---

## 📊 II. MA TRẬN FUNCTION & TRẠNG THÁI TRIỂN KHAI THEO SRC CODE HIỆN TẠI

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Hiện trạng | Hành động cần hoàn thiện |
| :---: | :--- | :---: | :---: | :--- | :---: | :--- |
| **5.1** | `POST /campaigns/{id}/collaborations`<br>([CollaborationController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)) | Brand User | `SEP_BE` | **In**: `CreateCollaborationRequest`<br>**Out**: `CollaborationResponse` | 🟢 **ĐÃ HOÀN THÀNH** | Cho phép tạo từ `relationship.stage == COLLABORATING`; không bắt buộc `agreedFee` & `currency` (tự động fallback về `relationship`); liên kết `master_id`. |
| **5.2** | `GET /campaigns/{id}/collaborations`<br>(Brand Collaboration List) | Brand User | `SEP_BE` | **In**: `campaignId, page, size, status`<br>**Out**: `PageResponse<CollaborationSummaryDto>` | 🟢 **ĐÃ HOÀN THÀNH** | Trả về danh sách phân trang các hợp tác thuộc Campaign của Brand kèm tiến độ bài viết, link bài đăng, hasOutcome, hasReview. |
| **5.3** | `GET /collaborations/{id}`<br>(Collaboration Detail API) | Brand & KOC | `SEP_BE` | **In**: `collaborationId`<br>**Out**: `CollaborationDetailResponse` | 🟢 **ĐÃ HOÀN THÀNH** | Trả về chi tiết hợp tác: thông tin KOC, brief, link bài đăng, kết quả Outcome (nếu có) và Review (nếu có). Kiểm tra quyền Brand/KOC chặt chẽ. |
| **5.4** | `GET /creators/portal/collaborations`<br>(Creator Portal Collaboration List) | KOC User | `SEP_BE` | **In**: Bearer JWT KOC, `status`<br>**Out**: `List<CreatorCollaborationDto>` | 🟢 **ĐÃ HOÀN THÀNH** | Endpoint Creator Portal cho KOC truy vấn toàn bộ hợp tác được giao qua `CreatorMaster` hoặc `creator.master`. |
| **5.5** | `POST /collaborations/{id}/evidence`<br>`GET /collaborations/{id}/evidence`<br>(Nộp Bằng chứng Thực thi & Xem Deliverables) | KOC User / Brand User | `SEP_BE` | **In**: `SubmitEvidenceRequest`<br>**Out**: `EvidenceAssetResponse` | 🟢 **ĐÃ HOÀN THÀNH** | Tách rời ràng buộc cứng `outcome_id` qua Flyway `V18__evidence_asset_collaboration_support.sql`, hỗ trợ nộp link bài đăng (`publishedContentUrl`), ảnh Insights/Draft video trong lúc `IN_PROGRESS`. Tự nhận diện `source` (`CREATOR_SUBMITTED`/`BRAND_UPLOAD`) và nhúng trực tiếp vào `CollaborationDetailResponse`. |
| **5.6** | `POST /collaborations/{id}/outcome`<br>([CollaborationController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)) | Brand User | `SEP_BE` | **In**: `RecordOutcomeRequest`<br>**Out**: `OutcomeResponse` | 🟢 **ĐÃ HOÀN THÀNH** | Nghiệm thu hiệu quả tương tác mạng xã hội (Views, Clicks, Likes, Comments, Shares, Post URL), đóng `COMPLETED`, cập nhật CRM stage sang `COMPLETED`, gán `evidenceQuality = 'SELF_REPORTED'`. Tách rời hoàn toàn khỏi số liệu tài chính. |
| **5.7** | `POST /collaborations/{id}/review`<br>([CollaborationController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)) | Brand User | `SEP_BE` | **In**: `CreateCreatorReviewRequest`<br>**Out**: `CreatorReviewResponse` | 🟢 **ĐÃ LÀM** | Lưu đánh giá sao (1–5), mức độ phù hợp Brand Fit (1–5), xác nhận `wouldCollaborateAgain` và phản hồi định tính. |
| **5.8** | *Autonomous Post KPI Auto-Tracking*<br>(Tự động cào chỉ số bài đăng T+3, T+7) | AI Crawler | `creator-discovery` | **In**: `publishedContentUrl, platform`<br>**Out**: Public Views, Likes, Comments | 🔴 **CHƯA LÀM (P2)** | Worker quét định kỳ cào lại số liệu công khai của link bài đăng đã nộp, nâng hạng `evidenceQuality = 'PUBLIC_METRIC_OBSERVED'`. |
| **5.9** | *M1 Historical Collaboration Sync Listener*<br>(Đồng bộ sang Bộ nhớ Dài hạn) | Backend Core | `SEP_BE` | **In**: `CollaborationCompletedEvent`<br>**Out**: Tạo `HistoricalCampaign` & cập nhật `BrandPattern` | 🟡 **CẦN HOÀN THIỆN (P1)** | Lắng nghe sau bước Review để chuyển hóa kết quả nghiệm thu thành bản ghi M1 (`HistoricalCampaign`) phục vụ trực tiếp cho Flow 3 & Flow 6. |

---

## 🚀 III. CHI TIẾT CÁC PHASE TRIỂN KHAI THỰC TẾ

```
                          LỘ TRÌNH 6 PHASE TRIỂN KHAI FLOW 5
┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   PHASE 1    │ ──> │   PHASE 2    │ ──> │   PHASE 3    │ ──> │   PHASE 4    │
│  Stage Align │     │  Read APIs   │     │  Deliverable │     │  Non-Finance │
│  & Non-Money │     │  (Brand/KOC) │     │   Evidence   │     │   Outcome    │
└──────────────┘     └──────────────┘     └──────────────┘     └──────────────┘
                                                                       │
                                          ┌──────────────┐             │
                                          │   PHASE 6    │ <── ────────┘
                                          │ Acceptance   │     ┌──────────────┐
                                          │ Test Suite   │ <── │   PHASE 5    │
                                          └──────────────┘     │ Review & M1  │
                                                               │  Memory Sync │
                                                               └──────────────┘
```

---

### 🔹 PHASE 1: Collaboration Confirmation & Stage Alignment (`SEP_BE`) 🟢 [ĐÃ HOÀN THÀNH]
**Mục tiêu**: Loại bỏ hoàn toàn sự phụ thuộc vào logic thanh toán hợp đồng; đảm bảo khởi tạo Collaboration mượt mà từ quan hệ đã thương lượng ở Flow 4.

#### 1. Cập nhật điều kiện kích hoạt tại `CollaborationService.java`
* [x] **Mở rộng kiểm tra `relationship.getStage()`**: Chấp nhận cả 3 trạng thái hợp lệ từ phễu CRM Flow 4 (`COLLABORATING`, `INTERESTED`, `CONTACTED`).
* [x] **Tùy chọn hóa `agreedFee` & `currency`**: Bỏ `@NotNull` trên `agreedFee` và `@NotBlank` trên `currency` trong [CreateCollaborationRequest.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/dto/CreateCollaborationRequest.java). Tự động fallback lấy `agreedFee` và `currency` từ `relationship` nếu không truyền.
* [x] **Kế thừa định danh kép KOC**: Gán `master` từ `relationship.getMaster()` hoặc `creator.getMaster()` khi khởi tạo `Collaboration`.
* [x] **Bảo toàn tính tương thích ngược**: Giữ `paymentStatus` nội bộ để tương thích schema DB và hoàn thành 100% bộ unit test `CollaborationServiceTest`.

---

### 🔹 PHASE 2: Collaboration Read & Tracking APIs (`SEP_BE`) 🟢 [ĐÃ HOÀN THÀNH]
**Mục tiêu**: Cung cấp dashboard theo dõi tiến độ và chi tiết hợp tác cho cả Brand và Creator Portal.

#### 1. API cho Brand trong `CollaborationController.java`
* [x] `GET /campaigns/{campaignId}/collaborations`:
  * Trả về danh sách phân trang các hợp tác thuộc Campaign của Brand (`PageResponse<CollaborationSummaryDto>`).
  * Thông tin bao gồm: `id`, `campaignId`, `relationshipId`, `creatorId`, `creatorName`, `platform`, `avatarUrl`, `agreedFee`, `currency`, `status`, `publishedContentUrl`, `note`, `startAt`, `completedAt`, `updatedAt`, `hasOutcome`, `hasReview`.
* [x] `GET /collaborations/{collaborationId}`:
  * Trả về chi tiết hợp tác cụ thể (`CollaborationDetailResponse`), bao gồm thông tin chi tiết KOC, thỏa thuận nội dung, link bài đăng, kết quả Outcome (nếu đã nghiệm thu) và Review (nếu đã đánh giá).
  * Bảo mật: Xác thực quyền sở hữu của Brand hoặc KOC Master tương ứng.

#### 2. API cho Creator Portal trong `CreatorPortalCollaborationController.java`
* [x] `GET /creators/portal/collaborations`:
  * KOC truy vấn toàn bộ các hợp tác được giao thuộc quyền sở hữu của mình (`creator.master.user.id == actor.id` hoặc `collaboration.master.id == master.id`).
  * Trả về `List<CreatorCollaborationDto>`: tên chiến dịch, Brand Profile, yêu cầu bàn giao, link bài đăng, tiến độ và trạng thái nghiệm thu.

---

### 🔹 PHASE 3: Content Deliverables & Evidence Submission (`SEP_BE`) 🟢 [ĐÃ HOÀN THÀNH]
**Mục tiêu**: Cho phép KOC và Brand nộp đường dẫn bài đăng đã xuất bản và ảnh chụp Insights ngay trong quá trình thực thi (`IN_PROGRESS`).

#### 1. Mở endpoint nộp và xem bằng chứng
* [x] **Flyway Migration [V18__evidence_asset_collaboration_support.sql](file:///d:/SEP_BE/src/main/resources/db/migration/V18__evidence_asset_collaboration_support.sql)**:
  * Tách rời ràng buộc cứng `outcome_id NOT NULL` -> chuyển thành nullable.
  * Bổ sung cột `collaboration_id UUID REFERENCES collaboration(id) ON DELETE CASCADE` và index `idx_evidence_asset_collaboration_id`.
* [x] **Mở rộng Entity [EvidenceAsset.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/model/EvidenceAsset.java)**:
  * Thêm quan hệ `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "collaboration_id") Collaboration collaboration`.
* [x] **Repository [EvidenceAssetRepository.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/repository/EvidenceAssetRepository.java)**:
  * Truy vấn danh sách tài nguyên bằng chứng theo hợp tác: `findByCollaborationIdOrderByUploadedAtDesc(UUID collaborationId)`.
* [x] **Endpoints tại [CollaborationController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/controller/CollaborationController.java)**:
  * `POST /collaborations/{id}/evidence`: Cho phép KOC hoặc Brand nộp link bài đăng, ảnh Insights (`INSIGHTS_SCREENSHOT`, `POST_LINK`, `DRAFT_VIDEO`).
  * `GET /collaborations/{id}/evidence`: Trả về toàn bộ danh sách bằng chứng bàn giao của hợp tác.
  * Tự động xác định actor: nếu KOC (Creator Master) nộp thì gán `source = 'CREATOR_SUBMITTED'`, nếu Brand thì gán `source = 'BRAND_UPLOAD'`.
  * Tự động đồng bộ `publishedContentUrl` vào bảng `collaboration` khi có URL bài đăng được gửi kèm.
  * Nhúng danh sách `evidenceAssets` trực tiếp vào [CollaborationDetailResponse.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/dto/CollaborationDetailResponse.java).

---

### 🔹 PHASE 4: Non-Financial Outcome Recording & Social Engagement Metrics (`SEP_BE`) 🟢 [ĐÃ HOÀN THÀNH]
**Mục tiêu**: Nghiệm thu hiệu quả chiến dịch dựa trên chỉ số tương tác thực tế, hoàn toàn không dính dáng đến doanh thu hay ROI tài chính hợp đồng.

#### 1. Chuẩn hóa `RecordOutcomeRequest.java` & `recordOutcome()`
* [x] **Flyway Migration [V19__campaign_outcome_engagement_metrics.sql](file:///d:/SEP_BE/src/main/resources/db/migration/V19__campaign_outcome_engagement_metrics.sql)**:
  * Bổ sung các cột chỉ số tương tác mạng xã hội: `likes BIGINT`, `comments BIGINT`, `shares BIGINT` cùng check constraint `>= 0`.
* [x] **Entity [CampaignOutcome.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/model/CampaignOutcome.java)**:
  * Thêm các thuộc tính `likes`, `comments`, `shares`.
* [x] **DTOs [RecordOutcomeRequest.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/dto/RecordOutcomeRequest.java) & [OutcomeResponse.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/dto/OutcomeResponse.java)**:
  * Tiếp nhận đầy đủ các chỉ số: `views`, `clicks`, `likes`, `comments`, `shares`.
  * Hỗ trợ backward compatibility với constructor phụ cho các test hiện hữu.
  * Validator `isMetricsProvided()` chấp nhận khi có ít nhất một chỉ số đo lường bất kỳ được cung cấp.
* [x] **Service & Controller Logic**:
  * Linh hoạt nhận diện `publishedContentUrl`: tự động tái sử dụng URL bài đăng đã nộp ở Phase 3 nếu Brand không nhập lại trong request.
  * Phân biệt rõ giữa giá trị `0` (không có tương tác) và giá trị `null` (chưa có số liệu đo lường).
  * Tự động gán nhãn độ tin cậy: `evidenceQuality = 'SELF_REPORTED'`.
  * Chuyển trạng thái: `Collaboration.status = COMPLETED`, `completedAt = now()`.
  * Cập nhật đồng bộ quan hệ CRM: `BrandCreatorRelationship.stage = COMPLETED`.

---

### 🔹 PHASE 5: Creator Review & M1 Historical Collaboration Memory Sync (`SEP_BE`)
**Mục tiêu**: Ghi nhận đánh giá định tính của Brand và tự động chuyển hóa thành tri thức thực chiến cho Bộ nhớ AI M1 và M4.

#### 1. Giữ nguyên và kiểm thử hoàn chỉnh `POST /collaborations/{id}/review`
* Tiếp nhận đánh giá:
  * `overallRating`: Đánh giá tổng quan (1–5 sao).
  * `brandFitRating`: Mức độ phù hợp với phong cách thương hiệu (1–5 sao).
  * `wouldCollaborateAgain`: Brand có sẵn sàng tiếp tục hợp tác với KOC này không (Boolean).
  * `note`: Nhận xét chi tiết về tác phong làm việc, đúng hạn cam kết.

#### 2. Kích hoạt Event Listener đồng bộ Bộ nhớ AI
* Tạo `CollaborationCompletedEventListener.java` lắng nghe sự kiện sau khi Review thành công:
  * Trích xuất kết quả tạo bản ghi **Bộ nhớ 1 ([HistoricalCampaign.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/campaign/model/HistoricalCampaign.java) - M1)**:
    * `creator_id` & `master_id`: Định danh KOC thực chiến.
    * `evidence_quality = 'VERIFIED'`.
    * `reliability_score`: Tính toán chuẩn hóa từ `overallRating` và `brandFitRating` (ví dụ: rating 5 sao $\to$ reliability 1.0).
  * Cập nhật **Bộ nhớ 4 ([BrandPattern.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/brand/model/BrandPattern.java) - M4)**:
    * Gia tăng `evidence_count += 1`.
    * Nâng cao độ tin cậy tri thức `confidence` cho các Niche và Content Style được Brand đánh giá cao.

---

### 🔹 PHASE 6: Automated Acceptance Test Suite & Post Tracking Proposal
**Mục tiêu**: Kiểm thử tự động 100% kịch bản Flow 5 và đặc tả module Crawler đối soát tương tác bài đăng.

#### 1. Bộ kiểm thử tự động toàn diện: `Flow5CollaborationAcceptanceTest.java`
Xây dựng 10 kịch bản kiểm thử chuẩn mực (`TEST-F5-01` $\to$ `TEST-F5-10`):
* `TEST-F5-01 [Collab From Collaborating]`: Tạo Collaboration thành công từ Relationship ở giai đoạn `COLLABORATING`.
* `TEST-F5-02 [Collab Anti-Duplicate]`: Chặn tạo trùng lặp Collaboration cho cùng một Relationship (Unique Constraint).
* `TEST-F5-03 [Brand Query List]`: Brand truy vấn danh sách KOC đang hợp tác trong Campaign (`GET /campaigns/{id}/collaborations`).
* `TEST-F5-04 [Brand Collab Detail]`: Brand xem chi tiết tiến độ một hợp tác cụ thể (`GET /collaborations/{id}`).
* `TEST-F5-05 [Creator Portal View]`: KOC xem danh sách công việc hợp tác được giao qua Creator Portal.
* `TEST-F5-06 [Submit Evidence]`: KOC nộp link bài đăng và ảnh chụp Insights khi trạng thái là `IN_PROGRESS`.
* `TEST-F5-07 [Record Outcome Metrics]`: Brand nghiệm thu ghi nhận Views, Clicks, chuyển `status = COMPLETED`.
* `TEST-F5-08 [Outcome No Financial ROI]`: Nghiệm thu thành công không cần trường doanh thu/chi phí và không tính ROI tài chính.
* `TEST-F5-09 [Creator Review]`: Brand đánh giá sao, `brandFitRating` và `wouldCollaborateAgain`.
* `TEST-F5-10 [Memory Sync]`: Hoàn tất Review tự động sinh bản ghi `HistoricalCampaign` M1 với `evidenceQuality = VERIFIED`.

#### 2. Đề xuất Autonomous Post Tracking Worker (`creator-discovery` - P2)
* Viết worker `app/workers/post_kpi_tracker.py` nhận `publishedContentUrl`:
  * Quét định kỳ T+3 và T+7 ngày sau khi KOC nộp bài.
  * Cập nhật số lượt view/like thực tế quan sát được từ nền tảng công khai.
  * Gán nhãn `evidenceQuality = 'PUBLIC_METRIC_OBSERVED'`.

---

## 🎯 IV. LỢI ĐIỂM KHI BẢO VỆ CAPSTONE TRƯỚC HỘI ĐỒNG

1. **Định vị nghiệp vụ chuẩn xác**: Hội đồng sẽ đánh giá rất cao việc nhóm tập trung vào thế mạnh cốt lõi là **AI Matching, Content Delivery & Performance Tracking**, thay vì sa đà vào bài toán tài chính/escrow vốn không thuộc phạm vi của hệ thống AI Discovery.
2. **Loại bỏ rủi ro bảo mật & pháp lý thanh toán**: Không cần xử lý đối soát tài khoản ngân hàng, không lưu số tài khoản KOC, không lo tranh chấp bồi thường cọc hay hóa đơn GTGT.
3. **Mạch dữ liệu khép kín cho AI (Closed-loop AI Learning)**: Chứng minh được chu trình khép kín hoàn hảo: **Discovery (Flow 2) $\to$ AI Recommend (Flow 3) $\to$ Outreach/CRM (Flow 4) $\to$ Collaboration & Outcome (Flow 5) $\to$ M1/M4 Learning (Flow 6)**.

---

## ✅ V. IMPLEMENTATION TRACKING CHECKLIST

### Phase 1: Collaboration Confirmation & Stage Alignment
- [ ] Cập nhật `CollaborationService.create()` chấp nhận `RelationshipStage.COLLABORATING`.
- [ ] Chuyển `agreedFee` và `currency` thành trường tùy chọn (optional reference) trong `CreateCollaborationRequest`.
- [ ] Không tự động gán `paymentStatus = PENDING` trên giao diện trả về cho Brand.

### Phase 2: Collaboration Read & Tracking APIs (Ưu tiên P0)
- [ ] Thêm endpoint `GET /campaigns/{campaignId}/collaborations` cho Brand trong `CollaborationController`.
- [ ] Thêm endpoint `GET /collaborations/{collaborationId}` xem chi tiết hợp tác trong `CollaborationController`.
- [ ] Thêm endpoint `GET /creators/portal/collaborations` cho KOC trong Creator Portal.

### Phase 3: Content Deliverables & Evidence Submission (Ưu tiên P1)
- [ ] Cho phép cập nhật `publishedContentUrl` khi hợp tác ở trạng thái `IN_PROGRESS`.
- [ ] Mở endpoint `POST /collaborations/{id}/evidence` tiếp nhận link bài đăng hoặc ảnh chụp màn hình Insights.
- [ ] Cung cấp API cho Brand xem và kiểm tra bằng chứng do KOC nộp.

### Phase 4: Non-Financial Outcome Recording & Social Engagement Metrics
- [ ] Chuẩn hóa `RecordOutcomeRequest` tập trung vào số liệu tương tác (Views, Clicks, Likes, Shares) và ghi chú thực tế.
- [ ] Phân biệt rõ giữa số liệu chưa có (`null`) và số liệu bằng `0`.
- [ ] Đánh dấu `evidenceQuality = 'SELF_REPORTED'` và chuyển hợp tác sang `COMPLETED`.

### Phase 5: Creator Review & M1 Historical Collaboration Memory Sync
- [ ] Kiểm thử hoàn thiện `POST /collaborations/{id}/review` với các tiêu chí `overallRating`, `brandFitRating`, `wouldCollaborateAgain`.
- [ ] Viết Event Listener tự động trích xuất bản ghi M1 `HistoricalCampaign` sau khi có Review.
- [ ] Cập nhật độ tin cậy và số lượng bằng chứng trong M4 `BrandPattern`.

### Phase 6: Automated Acceptance Test Suite & Post Tracking Proposal
- [ ] Xây dựng test suite `Flow5CollaborationAcceptanceTest.java` với 10 kịch bản kiểm thử (`TEST-F5-01` $\to$ `TEST-F5-10`).
- [ ] Chạy kiểm thử tự động với Maven (`mvn test -Dtest=Flow5CollaborationAcceptanceTest`).
- [ ] Đề xuất worker cào tự động đối soát chỉ số bài đăng `post_kpi_tracker.py` trong `creator-discovery` (P2).
