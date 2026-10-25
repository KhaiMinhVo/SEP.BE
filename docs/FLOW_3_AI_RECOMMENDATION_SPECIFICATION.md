# 🧠 FLOW 3: AI RECOMMENDATION USING FOUR-MEMORY FRAMEWORK & CAMPAIGN SHORTLIST MANAGEMENT
## Đề Xuất KOC, Tiến Hóa Bối Cảnh & Quản Trị Giỏ Hàng Tuyển Chọn (Production Specification)

> **Tài liệu đặc tả kỹ thuật, luồng nghiệp vụ & trạng thái triển khai thực tế trên mã nguồn**  
> **Repository áp dụng:** `D:\SEP_BE` (Core Backend) & `D:\creator-discovery` (AI Microservice)  
> **Phiên bản:** v5.0 (Cập nhật 100% theo mã nguồn hiện tại sau khi hoàn thành Phase 1 → Phase 6)  
> **Mục tiêu:** Kết nối trí tuệ nhân tạo (Explainable AI & Four-Memory Framework) với hệ thống quản trị quan hệ KOC (CRM Shortlist Cart) theo quy trình khép kín, bảo toàn dữ liệu và tối ưu trải nghiệm người dùng.

---

## 1. LUỒNG NGHIỆP VỤ TỔNG THỂ

```
[Brand bấm "Đề xuất KOC bằng AI"]
              │
              ▼
[Kiểm tra & Tạm giữ Quota AI (Two-Phase Quota Reservation)]
              │
              ▼
[Nạp 4 Tầng Bộ Nhớ (M1 Historical, M2 Public Fit, M3 Campaign Context, M4 Brand Pattern)]
              │
              ▼
[Native Query pgvector c.embedding (Hybrid Candidate Retrieval - Top 50 ứng viên)]
              │
              ▼
[Four-Memory Composite Scorer & Master-aware Grouping (Khử trùng lặp đa kênh)]
              │
              ▼
[XAI Engine /explain (Sinh Reasoning Card kèm Fallback Circuit Breaker)]
              │
              ▼
   ┌─────────────────────────────────────────────────────────────┐
   │                                                             │
   ▼                                                             ▼
[Brand Chấp nhận: 1-Click Thêm vào Shortlist Cart]   [Brand Bác bỏ: Nhập lý do (Feedback Loop)]
   │                                                             │
   ▼                                                             ▼
[Quản lý giỏ hàng, So sánh KOC & Đánh giá Contact Readiness]  [Negative Constraints: Loại trừ KOC & Tiến hóa Bối cảnh v_N+1]
   │                                                             │
   ▼                                                             ▼
[Outreach / Chuyển tiếp sang CRM Hợp tác]             [Lần gợi ý sau: Tự động loại trừ KOC đã Reject, giữ nguyên Budget]
```

---

## 2. CHI TIẾT CÁC FUNCTION CON & TRẠNG THÁI TRIỂN KHAI

| STT | Function / Endpoint | Actor liên quan | Repo | Input / Output | Trạng thái | Mô tả kỹ thuật & Hiện thực mã nguồn |
| :---: | :--- | :--- | :---: | :--- | :---: | :--- |
| **3.1** | `SubscriptionService.reserveRecommendationQuota()` & `releaseRecommendationQuota()` | Quota Guard / Brand User | `SEP_BE` | **In:** `brandProfileId` (UUID)<br>**Out:** `subscriptionId` (UUID) / `409 QUOTA_EXCEEDED` / `403 FORBIDDEN` | 🟢 **ĐÃ LÀM HOÀN TẤT** | **Two-Phase Quota Reservation**: Khóa tạm giữ 1 lượt nguyên tử qua `subscriptionRepository.reserveQuotaAtomically`. Nếu AI downstream timeout hoặc lỗi mạng, tự động rollback hoàn trả lượt qua `releaseRecommendationQuota` (Bảo vệ Brand không mất lượt oan - `TEST-01`, `TEST-02`). |
| **3.2** | `CampaignContextRepository` | Backend Engine / Brand User | `SEP_BE` | **In:** `campaignId`<br>**Out:** `List<CampaignContext>` (`contextVersion DESC`) | 🟢 **ĐÃ LÀM HOÀN TẤT** | **Quản lý Bối cảnh M3 & Negative Constraints**: Truy xuất ngữ cảnh phiên bản mới nhất. Trích xuất mảng JSON `excludedCreatorIds` để tự động loại trừ các ứng viên đã bị từ chối khỏi các lượt tìm kiếm vector tiếp theo (`TEST-10`). |
| **3.3** | `HistoricalCampaignRepository` & `BrandPatternRepository` | Four-Memory Engine | `SEP_BE` | **In:** `creatorId`, `brandProfileId`<br>**Out:** `HistoricalCampaign`, `BrandPattern` entities | 🟢 **ĐÃ LÀM HOÀN TẤT** | **Bộ nhớ M1 & M4**: Flyway migration `V17`, các JPA Entity `HistoricalCampaign`, `CampaignOutcome`, `CreatorReview` (M1) và `BrandPattern` (M4). KOC mới chưa có lịch sử hợp tác được tự động gán điểm trung tính **50.0 điểm** (`TEST-05`). |
| **3.4** | `PublicCreatorMetricRepository.findTopCandidatesByEmbedding()` | Database / Backend Engine | `SEP_BE` | **In:** `briefVector` (vector 768d), `excludedIds`, `platforms`, `followerMin`, `followerMax`, `limit=50`<br>**Out:** `List<RecommendationCandidateProjection>` | 🟢 **ĐÃ LÀM HOÀN TẤT** | **Hybrid Candidate Retrieval**: Native SQL query pgvector Cosine Distance (`c.embedding <=> CAST(:briefVector AS vector)`) chuẩn xác trên cột `vector(768)` của bảng `creator`, kết nối với bản ghi metric mới nhất theo `observed_at DESC NULLS LAST` (`TEST-03`). |
| **3.5** | `POST /campaigns/{campaignId}/ai-recommendations`<br>([AiRecommendationController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/recommendation/controller/AiRecommendationController.java)) | Brand User | `SEP_BE` | **In:** `campaignId`, `limit = 10`<br>**Out:** `ApiResponse<RecommendationResponseDto>` | 🟢 **ĐÃ LÀM HOÀN TẤT** | **Single Source of Truth Reranker**: Tính toán FinalScore kết hợp $0.50 \times M_2 + 0.30 \times M_1 + 0.20 \times M_4$. Trong đó $M_2$ áp dụng hàm $\log_{10}$ phi đối xứng, chuẩn hóa ER $5\%$ cho video ngắn, Min-Max rescaling $[0.30, 0.85] \to [0.0, 100.0]$. Tích hợp **Master-aware Grouping** khử trùng lặp đa kênh của cùng 1 KOC (`TEST-04`). |
| **3.6** | `AiServiceClient.getExplanation()` & Reasoning Card | AI Service / Backend | `SEP_BE` & `creator-discovery` | **In:** `ExplainRequestDto`<br>**Out:** `ReasoningCardDto` (`summary`, `reasons`, `explanationAvailable`) | 🟢 **ĐÃ LÀM HOÀN TẤT** | **XAI Engine với Circuit Breaker**: Kết nối Python AI microservice (`POST /api/v1/ai/explain`). Nếu Python service bị timeout hoặc lỗi mạng, tự động kích hoạt Circuit Breaker trả về thẻ mặc định với cờ `explanationAvailable = false`, không làm gián đoạn đề xuất và không giả mạo lý giải. |
| **3.7** | `POST /campaigns/{campaignId}/shortlist/items`<br>([ShortlistController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/controller/ShortlistController.java)) | Brand User | `SEP_BE` | **In:** `creatorAccountId`/`creatorId`, `priority`, `source`<br>**Out:** `ApiResponse<ShortlistItemDto>` | 🟢 **ĐÃ LÀM HOÀN TẤT** | **1-Click Candidate Cart (Flow SL-01)**: Cơ chế Idempotent chống lỗi HTTP 409. Nếu đã có trong Shortlist, trả về `action = ALREADY_EXISTS`, giữ nguyên ghi chú cá nhân của Brand và trả về `totalShortlistCount`. Nếu KOC từng bị soft-delete thì hồi phục trạng thái `SAVED` (`TEST-06`). |
| **3.8** | `GET`, `PATCH`, `DELETE` Shortlist Items | Brand User | `SEP_BE` | **In:** `status`, `priority`, `platform`, `pageable`<br>**Out:** `Page<ShortlistItemDto>`, update note/status, batch update | 🟢 **ĐÃ LÀM HOÀN TẤT** | **View & Manage Shortlist (Flow SL-02)**: Hỗ trợ lọc đa tiêu chí bằng JPA Specification. Endpoint `PATCH /batch-status` cập nhật hàng loạt trạng thái. Endpoint `DELETE` thực hiện **Soft Delete** (`status = REMOVED`), bảo toàn tuyệt đối dữ liệu CRM trong bảng `relationship` (`TEST-07`). |
| **3.9** | `POST /campaigns/{campaignId}/shortlist/compare` | Brand User | `SEP_BE` | **In:** `creatorIds` (2–5 KOCs)<br>**Out:** `CompareDtos.Response` (Followers, Views, Likes, ER, Niche, Verified...) | 🟢 **ĐÃ LÀM HOÀN TẤT** | **Review & Compare Creators (Flow SL-03)**: So sánh trực quan các KOC trong giỏ hàng thông qua [ShortlistCompareService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/service/ShortlistCompareService.java). Hoàn toàn là thao tác nội bộ của Brand, **không gọi AI và không trừ Quota** (`TEST-08`). |
| **3.10** | `POST /campaigns/{campaignId}/shortlist/contact-readiness` | Brand User | `SEP_BE` | **In:** `itemIds` (danh sách KOC cần liên hệ)<br>**Out:** `ApiResponse<ContactReadinessResponse>` | 🟢 **ĐÃ LÀM HOÀN TẤT** | **Contact Selection & Preview (Flow SL-04)**: Tự động đánh giá 5 trường hợp liên hệ: Đã claim kênh (`IN_APP_PORTAL`), Chưa claim nhưng có email booking trong bio/contact (`SYSTEM_EMAIL_OUTBOX` - `TEST-09`), Chỉ có social link (`MANUAL_CONTACT`), Không có liên hệ (`UNAVAILABLE`), hoặc Đã có lời mời trong CRM (`ALREADY_CONTACTED`). |
| **3.11** | `POST /campaigns/{campaignId}/ai-recommendations/feedback`<br>([AiRecommendationController.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/recommendation/controller/AiRecommendationController.java)) | Brand User | `SEP_BE` | **In:** `creatorId`, `action` (`REJECT`/`ACCEPT`), `reasonCode`, `reasonNote`<br>**Out:** `ApiResponse<RecommendationFeedbackResponse>` | 🟢 **ĐÃ LÀM HOÀN TẤT** | **Feedback Loop Endpoint (Phase 4)**: Tiếp nhận phản hồi của Brand khi từ chối hoặc đánh giá KOC. Tự động thêm KOC vào `excludedCreatorIds`, lưu lịch sử vào `feedbackHistory`, cập nhật học tập M4 `BrandPattern` và kích hoạt tiến hóa bối cảnh. |
| **3.12** | `Negative Constraints Adaptation & Context Evolution` | Machine Learning / Backend | `SEP_BE` | **In:** `reasonCode: PRICE_TOO_HIGH`...<br>**Out:** Evolved Context ($v_N \to v_{N+1}$), Campaign preserved | 🟢 **ĐÃ LÀM HOÀN TẤT** | **Tiến hóa Ngữ cảnh & Ràng buộc An toàn**: Lưu trữ bản ghi cũ (`active = false`), tạo bản ghi mới với `contextVersion = v_N + 1`. KOC bị từ chối sẽ bị loại bỏ vĩnh viễn khỏi các lần gợi ý sau của chiến dịch. **QUY TẮC AN TOÀN: Tuyệt đối KHÔNG tự động hạ `budgetMax` hay xóa `niches` của Campaign** (`TEST-10`). |
| **3.13** | `Flow3ComprehensiveAcceptancePhase6Test.java` | QA / CI/CD Automation | `SEP_BE` | **In:** 10 Kịch bản kiểm thử nghiệm thu (`TEST-01` → `TEST-10`)<br>**Out:** 10/10 Tests Passed (Tổng 38/38 Unit Tests) | 🟢 **ĐÃ LÀM HOÀN TẤT** | **Bộ Kiểm Thử Nghiệm Thu Toàn Diện**: Bao phủ 100% các tình huống biên: Quota Concurrency Guard, Rollback on Failure, pgvector SQL, Deduplication, M1 Default 50.0, Cart Idempotency, CRM Soft Delete, Compare No Quota, Booking Email Detection, và Safe Feedback Evolution. |

---

## 3. CÔNG THỨC TOÁN HỌC TRONG COMPOSITE RANKING (BACKEND SOT)

$$\text{FinalScore} = 0.50 \times \text{Score}_{M2} + 0.30 \times \text{Score}_{M1} + 0.20 \times \text{Score}_{M4}$$

1. **Điểm Phù Hợp KOC ($M_2$)**:
   $$\text{Score}_{M2} = 0.20 \times \text{followerScore} + 0.25 \times \text{engagementScore} + 0.30 \times \text{semanticMatchScore} + 0.25 \times \text{nicheMatchScore}$$
   * **`followerScore`**: Hàm $\log_{10}$ phi đối xứng (phạt nặng 50đ/bậc khi thiếu, phạt nhẹ 30đ/bậc khi thừa, sàn 60đ khi vượt max).
   * **`engagementScore`**: Chuẩn hóa $5.0\%$ cho video ngắn (`avgLikes / avgViews`); chuẩn hóa theo Tier Benchmark cho mạng xã hội truyền thống.
   * **`semanticMatchScore`**: Min-Max Rescaling dải Cosine $[0.30, 0.85] \rightarrow [0.0, 100.0]$.
   * **`nicheMatchScore`**: So khớp 6 cấp độ động (Exact Niche $100$đ, Tag $90$đ, Category $85$đ, Semantic).
2. **Điểm Lịch Sử Hợp Tác ($M_1$)**:
   * Tính từ `campaign_outcome.roi`, `creator_review.brand_fit_rating`, và `would_collaborate_again`.
   * **KOC mới chưa có lịch sử nhận điểm trung tính $50.0$ điểm** (không bị phạt).
3. **Điểm Khẩu Vị Nhãn Hàng ($M_4$)**:
   * So khớp mức ER thực tế với `preferred_engagement_rate`, thù lao ước tính với `average_agreed_fee` trong `BrandPattern`.

---

## 4. MA TRẬN TRẠNG THÁI SHORTLIST CART (SL-01 → SL-04)

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
                 │ (Soft Remove)                  │
                 ▼                                │
             [REMOVED] ───────────────────────────┘
```

* **Phân định ranh giới trách nhiệm**:
  * **Shortlist Cart**: Quyết định tuyển chọn nội bộ của Brand (KOC chưa nhận bất kỳ thông báo nào).
  * **Contact Readiness**: Kiểm tra tính sẵn sàng của kênh liên hệ trước khi gửi lời mời (Outreach).
  * **Outreach / Collaboration**: Lời mời hợp tác chính thức (KOC nhận thông báo, bước vào phễu đàm phán CRM).
