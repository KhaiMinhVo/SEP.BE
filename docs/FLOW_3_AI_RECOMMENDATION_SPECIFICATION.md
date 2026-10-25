# 🧠 FLOW 3: AI RECOMMENDATION, FOUR-MEMORY RANKING & CAMPAIGN SHORTLIST MANAGEMENT
## InfluencerMatch AI – Production Architecture & Phased Implementation Specification

> **Tài liệu đặc tả kỹ thuật, phân kỳ triển khai (Phased Roadmap) & quy tắc nghiệp vụ**  
> **Repository áp dụng:** `D:\SEP_BE` (Core Backend) & `D:\creator-discovery` (AI Microservice)  
> **Phiên bản:** v4.0 (Phased Delivery Architecture, Four-Memory Composite Ranking & Complete Shortlist Management)  
> **Mục tiêu cốt lõi:** Kết nối trí tuệ nhân tạo (Explainable AI) với hệ thống quản trị quan hệ KOC (CRM) theo quy trình 6 giai đoạn khép kín, phân chia theo các Phase triển khai cụ thể, đảm bảo mã nguồn hiện tại được tái sử dụng tối đa.

---

## 📑 MỤC LỤC
1. [Báo Cáo Đối Soát Mã Nguồn Shortlist Hiện Tại](#1-báo-cáo-đối-soát-mã-nguồn-shortlist-hiện-tại)
2. [Workflow Nghiệp Vụ Tổng Thể 6 Giai Đoạn](#2-workflow-nghiệp-vụ-tổng-thể-6-giai-đoạn)
3. [PHASE 1: Nền Tảng Dữ Liệu & Ràng Buộc Cốt Lõi (P0)](#phase-1-nền-tảng-dữ-liệu--ràng-buộc-cốt-lõi-p0)
4. [PHASE 2: Four-Memory Ranking & Hybrid Candidate Retrieval (P0)](#phase-2-four-memory-ranking--hybrid-candidate-retrieval-p0)
5. [PHASE 3: Campaign Shortlist Management & Cart Engine (SL-01 → SL-04) (P1)](#phase-3-campaign-shortlist-management--cart-engine-sl-01--sl-04-p1)
6. [PHASE 4: Feedback Loop & Tiến Hóa Bối Cảnh M3 (Negative Constraints) (P1)](#phase-4-feedback-loop--tiến-hóa-bối-cảnh-m3-negative-constraints-p1)
7. [PHASE 5: Tinh Chỉnh Python AI Microservice (creator-discovery) (P1)](#phase-5-tinh-chỉnh-python-ai-microservice-creator-discovery-p1)
8. [PHASE 6: Ma Trận Kiểm Thử Tự Động & Tiêu Chí Nghiệm Thu (P0/P1)](#phase-6-ma-trận-kiểm-thử-tự-động--tiêu-chí-nghiệm-thu-p0p1)
9. [Phân Công Trách Nhiệm Dự Án (RACI Matrix)](#9-phân-công-trách-nhiệm-dự-án-raci-matrix)

---

## 1. BÁO CÁO ĐỐI SOÁT MÃ NGUỒN SHORTLIST HIỆN TẠI

Kiểm tra trực tiếp mã nguồn trong `D:\SEP_BE`: **HỆ THỐNG HOÀN TOÀN CÓ THỂ TRIỂN KHAI SHORTLIST THEO HƯỚNG NÀY VÌ ĐÃ CÓ SẴN 80% NỀN TẢNG!**

* **Entity [ShortlistItem.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/model/ShortlistItem.java)**:
  * Đã có sẵn ràng buộc chống trùng độc nhất: `ux_shortlist_campaign_creator` trên cặp `(campaign_id, creator_id)`.
  * Đã có sẵn trường `master_id` (Flyway `V8`) để liên kết với `CreatorMaster`.
  * Đã có sẵn Enum [ShortlistPriority.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/enums/ShortlistPriority.java) với 3 mức: `HIGH`, `MEDIUM`, `LOW`.
* **Repository [ShortlistItemRepository.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/repository/ShortlistItemRepository.java)**:
  * Đã có sẵn: `existsByCampaignIdAndCreatorId(campaignId, creatorId)`.
  * Đã có sẵn: `findByCampaignIdAndCreatorIdIn(campaignId, creatorIds)`.
* **Dịch vụ So sánh [ShortlistCompareService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/service/ShortlistCompareService.java)**:
  * **ĐÃ CÀI ĐẶT SẴN $100\%$ tính năng Compare 2–5 Creator (Flow SL-03)**: Kiểm tra danh sách hợp lệ, truy vấn metrics mới nhất (`metrics.latestForCreators(ids)`), so sánh Followers, Views, Likes, ER, Niche, Location, Verified Status mà không cần chạy lại AI Recommendation.
* **Controller [ShortlistController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/controller/ShortlistController.java)**:
  * Đã có sẵn các endpoint: `@PostMapping` (Add), `@GetMapping` (List), `@DeleteMapping` (Remove), `@PostMapping("/compare")` (Compare).
* **Những điểm cần tinh chỉnh để đạt 100% hướng mới**:
  1. Xóa việc ném lỗi `RESOURCE_ALREADY_EXISTS` (HTTP 409) trong [ShortlistServiceImpl.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/service/ShortlistServiceImpl.java#L39-L41); chuyển sang cơ chế Idempotent trả về `ALREADY_EXISTS` kèm `totalShortlistCount`.
  2. Bổ sung trường `status` (`SAVED`, `READY_TO_CONTACT`, `REMOVED`) và `source` (`AI_RECOMMENDATION`, `MANUAL_DISCOVERY`) vào `ShortlistItem`.
  3. Bổ sung Contact Readiness Check trước khi chuyển sang Outreach.

---

## 2. WORKFLOW NGHIỆP VỤ TỔNG THỂ 6 GIAI ĐOẠN

```
[1. AI Recommendation] (Match Score & XAI Reasoning Card)
          │
          ▼
[2. Add to Shortlist] (FLOW SL-01: Candidate Cart - 1-Click lưu ứng viên)
          │
          ▼
[3. View & Manage Shortlist] (FLOW SL-02: Phân loại Priority, Note, Lọc & Sắp xếp)
          │
          ▼
[4. Review & Compare] (FLOW SL-03: So sánh 2-5 KOC, Đánh dấu READY_TO_CONTACT)
          │
          ▼
[5. Contact Selection & Preview] (FLOW SL-04: Kiểm tra Readiness, Xác nhận Outreach)
          │
          ▼
[6. CRM / Negotiation & Collaboration] (Đàm phán Agreed Fee, Hợp tác, Đúc kết M1/M4)
```

### Nguyên tắc phân tách trách nhiệm tối thượng:
* **Shortlist = Candidate Selection & Decision Support**: Brand đang lưu ai để cân nhắc (hoàn toàn nội bộ, KOC chưa hề biết).
* **Outreach = Contact & Invitation**: Lời mời trao đổi đầu tiên (KOC nhận thông báo và có quyền từ chối/đồng ý).
* **Negotiation = Agreement Discussion**: Quá trình thương lượng chi phí, deliverables, timeline (phễu `relationship`).
* **Collaboration = Execution & Outcome**: Hợp đồng thực thi công việc và nghiệm thu KPI (bảng `collaboration` $\rightarrow$ sinh bằng chứng M1).

---

## 3. PHASE 1: NỀN TẢNG DỮ LIỆU & RÀNG BUỘC CỐT LÕI (P0)

*Mục tiêu: Đảm bảo toàn vẹn dữ liệu, sửa triệt để các lỗi cú pháp SQL và cơ chế bảo vệ hạn mức Quota.*

### Nhiệm vụ 1.1: Sửa Native Query pgvector chuẩn xác trên cột `c.embedding`
* **Vấn đề đã khắc phục**: File [`V4__creator_hybrid_search_vector.sql`](file:///d:/SEP_BE/src/main/resources/db/migration/V4__creator_hybrid_search_vector.sql) quy định:
  * `public_creator_metric.search_vector`: kiểu `tsvector` phục vụ Full-Text Search.
  * `creator.embedding`: kiểu `vector(768)` phục vụ pgvector Cosine Distance (`<=>`).
* **Triển khai trong [PublicCreatorMetricRepository.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/repository/PublicCreatorMetricRepository.java)**:
  ```sql
  SELECT c.id, c.master_id, c.platform, c.display_name, c.user_name, c.avatar_url,
         m.followers, m.avg_views, m.avg_likes, m.engagement_rate, m.niche, m.category, m.creator_type,
         (1 - (c.embedding <=> CAST(:briefVector AS vector))) AS semantic_score
  FROM creator c
  JOIN public_creator_metric m ON c.id = m.creator_id
  WHERE (:excludedIds IS NULL OR c.id NOT IN (:excludedIds))
    AND (:platforms IS NULL OR c.platform IN (:platforms))
    AND (:followerMin IS NULL OR m.followers >= :followerMin)
    AND (:followerMax IS NULL OR m.followers <= :followerMax)
    AND m.id = (
        SELECT m2.id FROM public_creator_metric m2 
        WHERE m2.creator_id = c.id 
        ORDER BY m2.observed_at DESC NULLS LAST LIMIT 1
    )
  ORDER BY c.embedding <=> CAST(:briefVector AS vector) ASC
  LIMIT 50;
  ```

### Nhiệm vụ 1.2: Cài đặt Two-Phase Quota Reservation trong [SubscriptionService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/billing/service/SubscriptionService.java)
* **Khóa dòng Atomic**:
  ```java
  @Modifying
  @Query("UPDATE Subscription s SET s.usedRecommendationQuota = s.usedRecommendationQuota + 1 " +
         "WHERE s.id = :id AND s.usedRecommendationQuota < s.plan.recommendationQuota")
  int reserveQuotaAtomically(@Param("id") UUID subscriptionId);
  ```
* **Cơ chế Rollback (Hoàn trả)**: Nếu bước gọi Python AI hoặc bước lưu DB gặp lỗi, tự động giải phóng quota:
  ```java
  @Modifying
  @Query("UPDATE Subscription s SET s.usedRecommendationQuota = s.usedRecommendationQuota - 1 " +
         "WHERE s.id = :id AND s.usedRecommendationQuota > 0")
  void releaseQuotaReservation(@Param("id") UUID subscriptionId);
  ```

### Nhiệm vụ 1.3: Tạo JPA Entity & Repository cho M1 & M4
* Tạo `HistoricalCampaign.java` (gắn `@ManyToOne` với `CampaignOutcome` và `Creator`) + `HistoricalCampaignRepository.java`.
* Tạo `BrandPattern.java` + `BrandPatternRepository.java`.
* Tạo Flyway `V9__recommendation_run.sql` lưu vết mỗi lần chạy AI Recommendation (`recommendation_run`).

---

## 4. PHASE 2: FOUR-MEMORY RANKING & HYBRID CANDIDATE RETRIEVAL (P0)

*Mục tiêu: Xây dựng đầu não xếp hạng KOC thực chất, kết hợp đa tầng bộ nhớ và loại bỏ trùng lặp đa kênh.*

### Nhiệm vụ 2.1: Four-Memory Feature Scorer trong `AiRecommendationService.java`
Java Backend là **Single Source of Truth** duy nhất quyết định điểm xếp hạng:
$$\text{FinalScore} = 0.50 \times \text{Score}_{M2} + 0.30 \times \text{Score}_{M1} + 0.20 \times \text{Score}_{M4}$$

1. **Điểm $M_2$ (Public Creator Fit)**:
   $$\text{Score}_{M2} = 0.20 \times \text{followerScore} + 0.25 \times \text{engagementScore} + 0.30 \times \text{semanticMatchScore} + 0.25 \times \text{nicheMatchScore}$$
   * `followerScore`: Áp dụng hàm $\log_{10}$ phi đối xứng (phạt 50đ/bậc khi thiếu, phạt nhẹ 30đ/bậc khi thừa, sàn 60đ).
   * `engagementScore`: Chuẩn hóa $5.0\%$ cho video ngắn (`avgLikes/avgViews`); đối chiếu Tier Benchmark cho MXH truyền thống.
   * `semanticMatchScore`: Min-Max Rescaling dải Cosine $[0.30, 0.85] \rightarrow [0.0, 100.0]$.
   * `nicheMatchScore`: So khớp 6 cấp độ động (Exact Niche $100$đ, Tag $90$đ, Category $85$đ, Semantic).
2. **Điểm $M_1$ (Historical Reliability)**:
   * Tính từ `campaign_outcome.roi`, `creator_review.brand_fit_rating`, và `would_collaborate_again`.
   * KOC mới chưa có lịch sử nhận điểm trung tính **$50.0$ điểm** (không bị phạt).
3. **Điểm $M_4$ (Brand Preference Match)**:
   * So khớp mức ER thực tế với `preferred_engagement_rate`, thù lao ước tính với `average_agreed_fee` trong `BrandPattern`.

### Nhiệm vụ 2.2: Master-aware Grouping (Khử trùng lặp KOC)
* Gom 50 ứng viên thô theo `COALESCE(c.master_id, c.id)`.
* Nếu 1 KOC có cả kênh TikTok và Instagram cùng điểm cao, chỉ giữ lại kênh phù hợp nhất làm đại diện trong Top 10.

### Nhiệm vụ 2.3: Tích hợp XAI Engine (`POST /api/v1/ai/explain`)
* Gọi sang Python AI Microservice để nhận Reasoning Card (1 câu `summary`, danh sách `reasons` tự nhiên, `scoreBreakdown`).
* Cài đặt Fallback Circuit Breaker: nếu Python timeout $\rightarrow$ trả về thẻ mặc định với cờ `explanationAvailable = false`, **không tạo bằng chứng giả mạo**.

---

## 5. PHASE 3: CAMPAIGN SHORTLIST MANAGEMENT & CART ENGINE (SL-01 → SL-04) (P1)

*Mục tiêu: Hiện thực hóa trải nghiệm lưu, quản lý và so sánh ứng viên mượt mà như giỏ hàng thương mại điện tử.*

```
                 SHORTLIST STATUS STATE MACHINE
                 
                 ┌────────────────────────────────┐
                 │                                │
                 ▼                                │
              [SAVED] ◄──────────────────┐        │
                 │                       │        │
                 │ (Mark Ready)          │ (Undo) │ (Re-add)
                 ▼                       │        │
          [READY_TO_CONTACT] ────────────┘        │
                 │                                │
                 │ (Delete / Remove)              │
                 ▼                                │
             [REMOVED] ───────────────────────────┘
```

### 5.1. FLOW SL-01: ADD TO SHORTLIST (1-Click Candidate Cart)
* **API**: `POST /campaigns/{campaignId}/shortlist/items`
* **Request Body**:
  ```json
  {
    "creatorAccountId": "e5b8f6c4-...",
    "recommendationRunId": "a1b2c3d4-...",
    "source": "AI_RECOMMENDATION",
    "priority": "HIGH"
  }
  ```
* **Xử lý Idempotent (Chống lỗi 409)**:
  * Kiểm tra qua `shortlistItemRepository.existsByCampaignIdAndCreatorId`.
  * **Nếu đã tồn tại**: Giữ nguyên `note` thủ công của Brand, cập nhật `priority` nếu có, trả về:
    ```json
    {
      "action": "ALREADY_EXISTS",
      "shortlistItemId": "item-uuid",
      "status": "SAVED",
      "isInShortlist": true,
      "totalShortlistCount": 6,
      "message": "Ứng viên đã có sẵn trong Shortlist"
    }
    ```
  * **Nếu chưa có**: Tạo mới `ShortlistItem` (status = `SAVED`), gắn `creator_id`, `master_id`, `recommendation_run_id`, trả về:
    ```json
    {
      "action": "ADDED",
      "shortlistItemId": "item-uuid",
      "status": "SAVED",
      "isInShortlist": true,
      "totalShortlistCount": 7,
      "message": "Đã thêm Trang Đào vào Shortlist"
    }
    ```

### 5.2. FLOW SL-02: VIEW & MANAGE SHORTLIST
* **API**: 
  * `GET /campaigns/{campaignId}/shortlist`: Lấy danh sách kèm phân trang, hỗ trợ filter theo `priority`, `platform`, `niche`, `status`.
  * `PATCH /campaigns/{campaignId}/shortlist/items/{itemId}`: Sửa `priority`, cập nhật `brandNote`, hoặc đổi `status` (`SAVED` $\leftrightarrow$ `READY_TO_CONTACT`).
  * `DELETE /campaigns/{campaignId}/shortlist/items/{itemId}`: Xóa khỏi Shortlist đang hoạt động (chuyển sang `REMOVED`).
* **Business Rule**: Nếu ứng viên đã có lịch sử Outreach hoặc Collaboration trong bảng `relationship`, việc Remove khỏi Shortlist **tuyệt đối không được xóa lịch sử CRM**.

### 5.3. FLOW SL-03: REVIEW & COMPARE CREATORS (Tận dụng `ShortlistCompareService`)
* **API**: `POST /campaigns/{campaignId}/shortlist/compare`
* **Request**: `{ "creatorIds": ["id1", "id2", "id3"] }` (chọn từ 2 đến 5 KOC).
* **Xử lý**: 
  * Mã nguồn [ShortlistCompareService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/service/ShortlistCompareService.java) hiện tại đã hoạt động hoàn hảo.
  * Trả về bảng so sánh chi tiết: Followers, Views, Likes, ER, Niche, Location, Verified Status, Priority, Note.
  * **Quy tắc**: Thao tác Compare là quyết định nội bộ của Brand, **không tự động gọi AI Recommendation và không trừ Quota**.

### 5.4. FLOW SL-04: CONTACT SELECTION & READINESS PREVIEW
* **Trigger**: Brand chọn các KOC ở trạng thái `READY_TO_CONTACT` và bấm *"Liên hệ ứng viên đã chọn"*.
* **Kiểm tra Contact Readiness**:
  
  | Trường hợp KOC | Kênh liên hệ xác định | Trạng thái hiển thị | Hành động khả dụng |
  | :--- | :--- | :---: | :--- |
  | **Đã Claim kênh**, có Creator Portal | Kênh Notification / Portal nội bộ | `READY` | Gửi Collaboration Invitation trực tiếp |
  | **Chưa Claim**, có Email Booking hợp lệ | Email Outbox hệ thống | `READY` | Gửi Email mời trao đổi chiến dịch |
  | **Chưa Claim**, chỉ có Profile Social URL | Chưa tích hợp gửi tin nhắn tự động | `MANUAL_CONTACT` | Cung cấp link để Brand liên hệ thủ công |
  | **Không có thông tin liên hệ** | Không tìm thấy email hay SĐT | `UNAVAILABLE` | Cảnh báo Brand tìm ứng viên thay thế |
  | **Đang có lời mời hoạt động** | Đã gửi lời mời trước đó | `ALREADY_CONTACTED` | Chặn gửi trùng lặp |

* **Quy tắc**: Tách biệt rõ **Creator Claim Invitation** (mời nhận kênh qua OAuth) và **Collaboration Invitation** (mời hợp tác chiến dịch).

---

## 6. PHASE 4: FEEDBACK LOOP & TIẾN HÓA BỐI CẢNH M3 (NEGATIVE CONSTRAINTS) (P1)

*Mục tiêu: Giúp AI học từ quyết định từ chối của Brand mà không làm hỏng các ràng buộc chiến dịch.*

* **API**: `POST /campaigns/{campaignId}/ai-recommendations/feedback`
* **Request Body**:
  ```json
  {
    "creatorId": "3fa85f64-...",
    "action": "REJECT",
    "reasonCode": "PRICE_TOO_HIGH",
    "reasonNote": "KOC báo giá 30 triệu, vượt mức chi trả"
  }
  ```
* **Chính sách xử lý khi Brand REJECT KOC**:
  1. **Hành động cốt lõi**: Bổ sung `creatorId` vào mảng JSON `excludedCreatorIds` trong `CampaignContext.contextData`.
  2. **Ghi nhận sở thích mềm**: Lưu `reasonCode` vào `feedbackHistory` để M4 phân tích xu hướng dài hạn.
  3. **Quy tắc an toàn**: **TUYỆT ĐỐI KHÔNG tự động hạ `budgetMax` hay xóa `niches` của Campaign!** (Brand chê 1 KOC đắt không có nghĩa là muốn giảm ngân sách của toàn bộ chiến dịch).
  4. **Tiến hóa phiên bản**: Đánh dấu bản ghi cũ `active = false`, tạo bản ghi mới `contextVersion = v_N + 1`, `active = true`, `updatedAt = NOW()`.

---

## 7. PHASE 5: TINH CHỈNH PYTHON AI MICROSERVICE (`creator-discovery`) (P1)

*Mục tiêu: Đảm bảo các công thức toán học chạy ổn định và nhất quán với Java Client.*

* [ ] Cập nhật DTO `ExplainRequest` trong [`ai_router.py`](file:///d:/creator-discovery/app/api/routers/ai_router.py): Nhận thêm `minFollowers`, `maxFollowers`, `targetFollowers`, `platform`, `avgViews`, `avgLikes`.
* [ ] Kiểm chuẩn hàm `_calculate_follower_score()` trong [`explanation_service.py`](file:///d:/creator-discovery/app/ai/services/explanation_service.py): Chạy đúng công thức $\log_{10}$ phi đối xứng, chặn sàn $60$đ khi vượt max, $0$đ khi thiếu hụt.
* [ ] Kiểm chuẩn hàm `_calculate_engagement_score()`: Tự động chia cho chuẩn $5.0\%$ đối với TikTok, Reels, Shorts; chia cho Benchmark Tier đối với MXH truyền thống.
* [ ] Kiểm chuẩn hàm `_calculate_semantic_score()`: Áp dụng Min-Max Rescaling dải $[0.30, 0.85] \rightarrow [0.0, 100.0]$.

---

## 8. PHASE 6: MA TRẬN KIỂM THỬ TỰ ĐỘNG & TIÊU CHÍ NGHIỆM THU (P0/P1)

*Bộ kiểm thử bắt buộc phải pass 100% trước khi nghiệm thu từng Phase:*

| Mã Test | Phase | Kịch bản kiểm thử (Test Scenario) | Kết quả kỳ vọng |
| :---: | :---: | :--- | :--- |
| **TEST-01** | Phase 1 | Bắn đồng thời 2 request khi tài khoản chỉ còn 1 lượt Quota | Chỉ 1 request thành công, request thứ hai nhận HTTP 409 `QUOTA_EXCEEDED`. |
| **TEST-02** | Phase 1 | Python AI Service bị timeout hoặc tắt server | Backend tự động rollback giải phóng Quota tạm giữ, không làm mất lượt của Brand. |
| **TEST-03** | Phase 1 | Chạy truy vấn Vector Search trên Database | SQL chạy thành công trên cột `c.embedding`, không bị lỗi kiểu dữ liệu `tsvector`. |
| **TEST-04** | Phase 2 | Một KOC có cả kênh TikTok và Instagram điểm cao | Danh sách Top 10 chỉ hiển thị 1 kênh đại diện tốt nhất của KOC đó. |
| **TEST-05** | Phase 2 | KOC chưa từng có lịch sử hợp tác trong quá khứ | Điểm M1 tự động gán trung tính $50.0$ điểm, không bị phạt điểm oan. |
| **TEST-06** | Phase 3 | Bấm *"Thêm vào Shortlist"* 2 lần liên tiếp trên 1 KOC | Trả về `ALREADY_EXISTS`, không bị lỗi 409, giữ nguyên ghi chú cá nhân của Brand. |
| **TEST-07** | Phase 3 | Xóa KOC khỏi Shortlist khi KOC đã có bản ghi trong CRM | KOC chuyển `status = REMOVED`, nhưng lịch sử đàm phán trong `relationship` vẫn được bảo toàn. |
| **TEST-08** | Phase 3 | Chọn 3 KOC và bấm So sánh (Compare) | Trả về bảng so sánh đầy đủ chỉ số, không bị trừ thêm Quota AI. |
| **TEST-09** | Phase 3 | Bấm Liên hệ KOC chưa Claim nhưng có email booking | Hệ thống nhận diện trạng thái `READY` và chuẩn bị Email Outbox. |
| **TEST-10** | Phase 4 | Brand Reject KOC với lý do `PRICE_TOO_HIGH` | KOC được đưa vào `excludedCreatorIds`, lần chạy sau không xuất hiện, ngân sách Campaign giữ nguyên. |

---

## 9. PHÂN CÔNG TRÁCH NHIỆM DỰ ÁN (RACI MATRIX)

| Thành viên | Vai trò | Trách nhiệm chính theo Phase | Module bàn giao |
| :--- | :--- | :--- | :--- |
| **Khải** | Backend Lead | • **Phase 1**: Sửa query pgvector `c.embedding`, Two-Phase Quota Reservation.<br>• **Phase 2**: Four-Memory Ranking Scorer & Master Grouping.<br>• **Phase 3**: Tinh chỉnh Idempotent Shortlist Cart & Contact Readiness.<br>• **Phase 4**: Feedback Loop Endpoint & Negative Constraints Adaptation. | `SubscriptionService.java`<br>`PublicCreatorMetricRepository.java`<br>`AiRecommendationService.java`<br>`ShortlistServiceImpl.java`<br>`ShortlistController.java` |
| **Vy** | AI Lead | • **Phase 2**: Kiểm chuẩn Circuit Breaker và DTO contract giữa Java & Python.<br>• **Phase 5**: Tinh chỉnh 4 công thức toán học ($\log_{10}$, ER $5\%$, Min-Max Rescaling) trong `explanation_service.py`. | `explanation_service.py`<br>`ai_router.py`<br>`AiServiceClient.java` |
| **Văn** | Frontend Lead | • **Phase 2**: UI Thẻ đề xuất KOC kèm Reasoning Card Modal.<br>• **Phase 3**: Nút 1-Click *"Thêm vào Shortlist"* với Toast mượt mà, Drawer *"Giỏ hàng KOC"*, Giao diện Compare 2-5 KOC và Contact Preview Modal. | `RecommendationView.tsx`<br>`ShortlistCartDrawer.tsx`<br>`ShortlistCompareModal.tsx`<br>`ContactPreviewModal.tsx` |
| **Ngọc** | Data Lead | • **Phase 1**: Đảm bảo Crawler cào đủ `avgViews`, `avgLikes` vào `public_creator_metric`.<br>• **Phase 2**: Giám sát việc sinh vector 768 chiều vào cột `creator.embedding`. | `java_mapper.py`<br>`tiktok_crawler.py` |
