> Integration note: account creation requires invited registration and email OTP. Provider OAuth in these legacy demo instructions is simulated and disabled by default; Google Login is removed. See [integration notes](be-vy-integration.md) for the current behavior and migration requirements.

# CREATOR MASTER IDENTITY MANAGEMENT
## CLAIM-BASED IDENTITY & OAUTH ACCOUNT LINKING – CODEBASE AUDIT & ARCHITECTURE IMPLEMENTATION CHECKLIST

**Hệ thống:** InfluencerMatch AI (SEP490)
**Mã nguồn đối soát trực tiếp:**
- Core Enterprise Backend: `D:\SEP_BE` (Java 17 / Spring Boot 3 / PostgreSQL 16 + pgvector / Flyway)
- Async AI & Crawler Engine: `D:\creator-discovery` (Python 3.11 / FastAPI / Celery / Playwright)
- Frontend Portal: `D:\INFLUENCERMATCH` (React 19 / TypeScript / Ant Design)
**Phương pháp cốt lõi:** Public Account Registry → Invitation → OAuth Proof of Control → Creator Master Creation → Cross-platform Account Linking

---

## MỤC LỤC
1. [Sự Thay Đổi Triết Lý Kiến Trúc (Paradigm Shift)](#1-sự-thay-đổi-triết-lý-kiến-trúc-paradigm-shift)
2. [Mô Hình Định Danh Tinh Gọn: Xóa Bỏ `owner_user_id`, Chuẩn Hóa `creator_master`](#2-mô-hình-định-danh-tinh-gọn-xóa-bỏ-owner_user_id-chuẩn-hóa-creator_master)
3. [Tái Cấu Trúc Architecture Security (Modularized Security Sub-packages)](#3-tái-cấu-trúc-architecture-security-modularized-security-sub-packages)
4. [Kế Hoạch Gộp Database Migrations Dưới V10 (Flyway Consolidation Plan)](#4-kế-hoạch-gộp-database-migrations-dưới-v10-flyway-consolidation-plan)
5. [Bảng Đối Soát Hiện Trạng Mã Nguồn (Codebase Gap Analysis)](#5-bảng-đối-soát-hiện-trạng-mã-nguồn-codebase-gap-analysis)
6. [Checklist Triển Khai Cho `creator-discovery` (Python)](#6-checklist-triển-khai-cho-creator-discovery-python)
7. [Checklist Triển Khai Cho `SEP_BE` (Java Spring Boot)](#7-checklist-triển-khai-cho-sep_be-java-spring-boot)
   - [7.1. Database Migrations (Flyway V8 & V9 Consolidation)](#71-database-migrations-flyway-v8--v9-consolidation)
   - [7.2. Ingestion Pipeline Decoupling & Advisory Lock](#72-ingestion-pipeline-decoupling--advisory-lock)
   - [7.3. Discovery Hybrid Grouping Query (Fixed PostgreSQL DISTINCT ON)](#73-discovery-hybrid-grouping-query-fixed-postgresql-distinct-on)
   - [7.4. Invitation & Profile Claim Engine](#74-invitation--profile-claim-engine)
   - [7.5. OAuth Verification & Atomic Master Creation](#75-oauth-verification--atomic-master-creation)
   - [7.6. Connect Additional Accounts Service (4 Scenarios)](#76-connect-additional-accounts-service-4-scenarios)
   - [7.7. Dual References & CRM/Shortlist Adaptation](#77-dual-references--crmshortlist-adaptation)
   - [7.8. Data Manager Conflict Resolution](#78-data-manager-conflict-resolution)
8. [Giải Quyết Bẫy Kỹ Thuật (Technical Gotchas & Critical Traps)](#8-giải-quyết-bẫy-kỹ-thuật-technical-gotchas--critical-traps)
9. [Bảng Kiểm Thử Nghiệm Thu (Acceptance Testing Matrix - 18 TCs)](#9-bảng-kiểm-thử-nghiệm-thu-acceptance-testing-matrix---18-tcs)
10. [Lộ Trình Triển Khai Review 2 (3-Week Implementation Roadmap)](#10-lộ-trình-triển-khai-review-2-3-week-implementation-roadmap)
11. [Checklist Triển Khai Chi Tiết Theo Từng Phase Lập Trình (Step-by-Step Coding Phases)](#11-checklist-triển-khai-chi-tiết-theo-từng-phase-lập-trình-step-by-step-coding-phases)

---

## 1. SỰ THAY ĐỔI TRIẾT LÝ KIẾN TRÚC (PARADIGM SHIFT)

```
CƠ CHẾ CŨ (Auto-create Master on Crawl):
Crawler cào KOC mới ──> Tạo CreatorMaster ngay ──> 1 KOC đa kênh bị tách thành nhiều Master ──> Data Manager duyệt Merge thủ công.
(Hệ quả: Phình to Master rác, phụ thuộc phán đoán chủ quan của con người khi merge, dữ liệu trùng lặp thiếu tin cậy).

CƠ CHẾ MỚI (Claim-based & OAuth Account Linking):
Crawler cào KOC mới ──> Lưu Platform Account (master_id = NULL) ──> Vẫn tham gia Discovery bình thường.
Brand Shortlist / Mời KOC ──> KOC nhận email & đăng nhập OAuth tài khoản đầu tiên ──> Xác thực Proof of Control ──> Tạo CreatorMaster lần đầu.
KOC vào Portal kết nối kênh thứ 2, 3 qua OAuth ──> Backend đối chiếu Account đã cào trước đó ──> Tự động liên kết vào cùng Master.
(Ưu điểm: Không sinh Master rác, liên kết dựa trên bằng chứng kỹ thuật Proof of Control, giảm 90% tải cho Data Manager).
```

---

## 2. MÔ HÌNH ĐỊNH DANH TINH GỌN: XÓA BỎ `owner_user_id`, CHUẨN HÓA `creator_master`

### 2.1. Quyết Định Kiến Trúc: Xóa Bỏ Hoàn Toàn `creator.owner_user_id` (V9 Cũ)
- Trong V9 cũ ([V9__creator_claim_and_rate_card.sql](file:///d:/SEP_BE/src/main/resources/db/migration/V9__creator_claim_and_rate_card.sql)), cột `owner_user_id` được sinh ra khi KOC chỉ claim đơn lẻ 1 kênh mạng xã hội.
- Khi bước sang bài toán **Liên kết đa kênh (Cross-platform Account Linking)**:
  - Nếu vừa giữ `c.owner_user_id` vừa thêm `c.master_id`, bảng `creator` sẽ bị **dư thừa dữ liệu (Data Redundancy)** và **nguy cơ lệch pha dữ liệu (Split-brain)** giữa hai nguồn chân lý.
  - Hơn nữa, việc vẽ thêm bảng `creator_master_member` (Agency/Trợ lý phân quyền) là **hoàn toàn thừa thãi** so với yêu cầu của đề tài SEP490.
- **Giải pháp dứt khoát**:
  1. **Xóa bỏ cột `creator.owner_user_id`**.
  2. **Không tạo bảng `creator_master_member`**.
  3. Bảng `creator` chỉ có **DUY NHẤT 1 khóa ngoại định danh** là `master_id UUID REFERENCES creator_master(id)`.
  4. Bảng `creator_master` sở hữu khóa ngoại `user_id UUID NOT NULL UNIQUE REFERENCES "user"(id)` để xác định duy nhất KOC nào sở hữu Master này.

### 2.2. Sơ Đồ Thực Thể Tinh Gọn 2 Tầng (2-Tier Clean Architecture)

```
┌────────────────────────────────────────────────────────┐
│                   User (Role: CREATOR)                 │
│  - Tài khoản đăng nhập Portal (Google OAuth)           │
│  - Email, FullName, Status: ACTIVE                     │
└──────────────────────────┬─────────────────────────────┘
                           │ 1 - 1 (Mỗi Creator User có đúng 1 Master)
                           ▼
┌────────────────────────────────────────────────────────┐
│                      creator_master                    │
│  - id UUID PRIMARY KEY                                 │
│  - user_id UUID NOT NULL UNIQUE REFERENCES "user"(id)  │  <-- Chủ sở hữu Master
│  - canonical_name: "Châu Bùi"                          │
│  - status: ACTIVE | SUSPENDED                          │
└──────────────────────────┬─────────────────────────────┘
                           │ 1 - N (Gom nhóm đa kênh)
                           ▼
┌────────────────────────────────────────────────────────┐
│                         creator                        │
│  - id UUID PRIMARY KEY (A1: TikTok, A2: YouTube, ...)  │
│  - master_id UUID REFERENCES creator_master(id)        │  <-- NULL nếu chưa claim, NOT NULL khi đã claim
│  - source_type: PUBLIC | AUTHORIZED                    │
│  - authorized_at: TIMESTAMP                            │
│  - (ĐÃ XÓA owner_user_id và KHÔNG CẦN master_member)  │
└────────────────────────────────────────────────────────┘
```

### 2.3. Lợi Ích Của Thiết Kế Tinh Gọn Này
1. **Duy nhất một Nguồn Chân Lý (Single Source of Truth)**:
   - Muốn biết kênh `creator` thuộc KOC nào: truy vấn qua `creator.master.user`.
   - Ràng buộc toàn vẹn dữ liệu được đảm bảo 100% bằng Foreign Key của PostgreSQL.
2. **Check Constraint `ck_creator_authorization` Cực Kỳ Sạch Sẽ**:
   ```sql
   CONSTRAINT ck_creator_authorization CHECK (
       (source_type = 'PUBLIC' AND master_id IS NULL AND authorized_at IS NULL) OR
       (source_type = 'AUTHORIZED' AND master_id IS NOT NULL AND authorized_at IS NOT NULL)
   );
   ```
3. **Tương thích ngược trong tầng Java**:
   - Trong Entity [Creator.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/model/Creator.java), ta chỉ cần cung cấp helper method:
     ```java
     public User getOwner() {
         return master != null ? master.getUser() : null;
     }
     ```
   - Mọi code cũ ở Service hay Test suite nếu có gọi `creator.getOwner()` vẫn chạy bình thường mà không cần sửa code hàng loạt!

---

## 3. TÁI CẤU TRÚC ARCHITECTURE SECURITY (MODULARIZED SECURITY SUB-PACKAGES)

Hiện tại, thư mục [security](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/security/) đang chứa phẳng tất cả các class liên quan đến JWT, OAuth handler, API Key filter và Permissions. Cần tái cấu trúc thành các module con chuyên biệt:

```
com.influencermatch.backend.security/
├── apikey/                                # Service-to-service Authentication (Crawler -> Java BE)
│   └── CreatorServiceKeyFilter.java       # Xác thực X-API-KEY cho endpoint /creators/ingest
│
├── evaluator/                             # SpEL Security Evaluators (@PreAuthorize custom expressions)
│   ├── CreatorSecurityEvaluator.java      # @creatorSecurity.isOwner(#creatorId) -> kiểm tra master.user
│   └── CampaignSecurityEvaluator.java     # Kiểm tra quyền Brand đối với Campaign
│
├── jwt/                                   # Stateless JWT Authentication & Lifecycle
│   ├── JwtAccessDeniedHandler.java        # 403 Forbidden handling
│   ├── JwtAuthenticationEntryPoint.java   # 401 Unauthorized handling
│   ├── JwtAuthenticationFilter.java       # Header "Bearer ..." extraction & SecurityContext binding
│   └── JwtTokenProvider.java              # Sinh, băm, ký và validate JWT access token
│
├── oauth/                                 # Platform OAuth2 Flow & Token Security
│   ├── GoogleLoginIntent.java             # Quản lý intent (BRAND vs CREATOR login)
│   ├── GoogleOAuthSuccessHandler.java     # Chuyển hướng thành công và tạo exchange code
│   ├── GoogleOAuthFailureHandler.java     # Xử lý khi user cancel hoặc từ chối cấp quyền Google
│   ├── OAuthStateTokenStore.java          # Lưu trữ state/nonce một lần (chống CSRF attack)
│   └── OAuthTokenEncryptionService.java   # Mã hóa đối xứng AES-256-GCM cho Access/Refresh Token
│
├── permission/                            # Core RBAC & Dynamic Permission Overrides
│   ├── Permission.java                    # Enum danh mục 50+ quyền của hệ thống
│   ├── Permissions.java                   # Static helper truy xuất User Context & require()
│   ├── RolePermissions.java               # Ma trận phân quyền mặc định theo Role
│   └── role/                              # Module cấu hình phân quyền động (từ V11)
│       ├── EffectivePermissionService.java
│       ├── RolePermissionConfig.java
│       └── RolePermissionOverride.java
│
├── service/                               # Spring Security Integration Bridge
│   └── UserDetailsServiceImpl.java        # Tải Principal từ User repository
│
└── SecurityConfig.java                    # Root Spring Security 6 Config (FilterChain definition)
```

---

## 4. KẾ HOẠCH GỘP DATABASE MIGRATIONS DƯỚI V10 (FLYWAY CONSOLIDATION PLAN)

Hiện tại, thư mục `db/migration` có 11 file (`V1` đến `V11`). Để giữ toàn bộ hệ thống Flyway **dưới V10 (từ V1 đến V9)** cho bản nghiệm thu Review 2, chúng ta thực hiện kế hoạch gộp (Squash & Consolidation) như sau:

### 4.1. Bảng Đối Chiếu Gộp Migrations (11 files ──> 7 files cơ sở)

| File Hiện Tại | Nội Dung Chính | Kế Hoạch Gộp (Target Version < V10) |
| :--- | :--- | :--- |
| `V1__init_schema.sql` | Toàn bộ schema khởi tạo bảng cốt lõi | **`V1__init_schema.sql`** (Giữ nguyên) |
| `V2__add_creator_fields_and_posts.sql` | Thêm cột display_name, avatar, bảng post | **`V2__creator_posts_and_metrics.sql`** (Giữ nguyên) |
| `V3__google_oauth2_login.sql` | external_identity, auth_exchange_code | **`V3__auth_identity_and_exchange.sql`** (Giữ nguyên) |
| `V4__rbac_auth_version.sql` | Cột version cho auth | Gộp vào **`V3`** |
| `V5__add_creator_search_vector_and_fts.sql`<br>`V6__add_fts_match_function.sql`<br>`V7__enable_pgvector_and_creator_embedding.sql` | pg_trgm, search_vector FTS, fts_match(), pgvector, embedding | Gộp thành **`V4__creator_hybrid_search_vector.sql`** |
| `V8__add_outcome_views_and_clicks.sql`<br>`V10__data_quality_review.sql` | Views/clicks outcome, quality review metrics | Gộp thành **`V5__campaign_outcomes_and_data_quality.sql`** |
| `V11__role_permission_overrides.sql` | Bảng cấu hình override quyền động | Chuyển thành **`V6__role_permission_overrides.sql`** |
| `V9__creator_claim_and_rate_card.sql` (Cũ) | Claim cũ và rate card | Chuyển thành **`V7__creator_legacy_claim_and_rate_card.sql`** (Bỏ cột `owner_user_id`) |
| **MỚI (Claim-Based Foundation)** | **Tạo `creator_master`, thêm `master_id` vào `creator`, xóa `owner_user_id`, dual references** | **`V8__claim_based_identity_foundation.sql`** |
| **MỚI (OAuth Claim & Linking)** | **Tạo `creator_invitation`, `creator_provider_identity`, `creator_account_verification`, `creator_identity_conflict`, `creator_identity_audit`** | **`V9__creator_claim_and_oauth_tables.sql`** |

> **Kết quả đạt được**: Toàn bộ hệ thống DB Schema nằm trọn vẹn trong **9 bản Migration (từ V1 đến V9)**, không vượt quá V10, cấu trúc cực kỳ sạch sẽ và sẵn sàng cho Review 2.

---

## 5. BẢNG ĐỐI SOÁT HIỆN TRẠNG MÃ NGUỒN (CODEBASE GAP ANALYSIS)

| Hạng mục / Module | Hiện trạng trong Codebase (`SEP_BE` & `creator-discovery`) | Tác động theo Phương án Mới | Đánh giá |
| :--- | :--- | :--- | :---: |
| **Bảng `creator`** | Đang lưu trực tiếp `platform`, `external_id`, `embedding vector(768)`, `owner_user_id`, `source_type`. | Xóa bỏ `owner_user_id`. Thêm cột `master_id UUID NULL REFERENCES creator_master(id)`. Sửa constraint `ck_creator_authorization` theo `master_id`. | 🟡 Cần sửa migration V8 |
| **Cột `search_vector`** | Đang nằm ở bảng `public_creator_metric` (Generated Column FTS `tsvector`). | Giữ nguyên tại `public_creator_metric`. Không đụng chạm vào bảng `creator`. | 🟢 Hoàn toàn tương thích |
| **Ingestion Service** | `findLockedByPlatformAndExternalId` -> nếu chưa có thì tạo mới `Creator`. | Sửa: Tạo `Creator` với `master_id = null`. Áp dụng Advisory Lock băm số nguyên `pg_advisory_xact_lock(hashtext(...))`. | 🟡 Cần sửa code Java |
| **Discovery Query** | `JOIN creator c ON m.creator_id = c.id` -> trả về từng Account đơn lẻ. | Sửa: Áp dụng Subquery/CTE nhóm theo `COALESCE('MASTER:' || c.master_id, 'ACCOUNT:' || c.id)` và `ORDER BY hybridScore DESC` ở vòng ngoài. | 🟡 Cần sửa query |
| **Role CREATOR** | Đã có sẵn trong `Role.java`, `RolePermissions.java`, `V11`. | Kế thừa hoàn toàn. Mỗi User có Role `CREATOR` sẽ sở hữu 1 bản ghi `creator_master` (1-1). | 🟢 Nền tảng sẵn có |
| **Lời mời (Invitation)** | Chưa có bảng hoặc logic nào trong cả 2 repo. | Tạo bảng `creator_invitation` (bổ sung rõ `brand_id` và `invited_by`) và service sinh token SHA-256. | 🔴 Cần viết mới (V9) |
| **Liên kết đa kênh** | Chưa có logic nhận OAuth kênh thứ 2 và link vào Master. | Tạo `CreatorAccountLinkService.java` xử lý 4 kịch bản liên kết tài khoản. | 🔴 Cần viết mới |
| **Facebook Crawler** | Dòng 74 của `facebook_crawler.py` đang dùng `clean_slug` làm `externalId`. | Đánh dấu `isStableExternalId = False` để phân loại vào hồ sơ chờ xác minh định danh (Provisional). | 🟡 Cần sửa code Python |

---

## 6. CHECKLIST TRIỂN KHAI CHO `creator-discovery` (PYTHON)

> **Nguyên tắc**: `creator-discovery` hoàn toàn độc lập với khái niệm Master. Nó chỉ chịu trách nhiệm cào dữ liệu công khai và đẩy sang Java.

- [ ] **Tệp [d:\creator-discovery\app\core\models.py](file:///d:/creator-discovery/app/core/models.py)**:
  ```python
  class CreatorInfo(BaseModel):
      platform: Platform
      externalId: str
      isStableExternalId: bool = True  # Facebook slug = False, TikTok/YouTube = True
      username: str
      displayName: str
      profileUrl: str
      avatarUrl: str | None = None
      followers: int
      bio: str | None = None
      publicEmail: str | None = None
      publicPhone: str | None = None
      bioCrossLinks: list[str] = Field(default_factory=list)

  class IngestPayload(BaseModel):
      creator: CreatorInfo
      recentPosts: list[Post] = Field(default_factory=list)
      aiEnrichment: AIEnrichment | None = None
      source: str
      crawlRunId: str = Field(default_factory=lambda: f"CR-{uuid.uuid4().hex[:12].upper()}")
      observedAt: datetime = Field(default_factory=lambda: datetime.now(UTC))
      collectedAt: datetime = Field(default_factory=lambda: datetime.now(UTC))
  ```
- [ ] **Tệp [d:\creator-discovery\app\crawlers\facebook_crawler.py](file:///d:/creator-discovery/app/crawlers/facebook_crawler.py)**:
  - Cập nhật dòng gán `CreatorInfo`: gán rõ `isStableExternalId = False` vì Facebook slug có thể đổi.
- [ ] **Tệp [d:\creator-discovery\app\mappers\java_mapper.py](file:///d:/creator-discovery/app/mappers/java_mapper.py)**:
  - Cập nhật `JavaMapper.from_ingest_payload()` để gửi đủ các trường: `crawlRunId`, `observedAt`, `isStableExternalId`, `bioCrossLinks`.

---

## 7. CHECKLIST TRIỂN KHAI CHO `SEP_BE` (JAVA SPRING BOOT)

### 7.1. Database Migrations (Flyway V8 & V9 Consolidation)

#### Tệp `V8__claim_based_identity_foundation.sql`:
```sql
-- D:\SEP_BE\src\main\resources\db\migration\V8__claim_based_identity_foundation.sql

-- 1. Tạo bảng Creator Master (Đại diện cho KOC con người thật, quan hệ 1-1 với User Role CREATOR)
CREATE TABLE creator_master (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES "user"(id),
    canonical_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, SUSPENDED
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_creator_master_user ON creator_master(user_id);

-- 2. Xóa bỏ hoàn toàn owner_user_id trên bảng creator và chuẩn hóa master_id
ALTER TABLE creator DROP CONSTRAINT IF EXISTS ck_creator_authorization;
DROP INDEX IF EXISTS ix_creator_owner;
ALTER TABLE creator DROP COLUMN IF EXISTS owner_user_id;

ALTER TABLE creator ADD COLUMN master_id UUID;
ALTER TABLE creator ADD COLUMN is_stable_id BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE creator ADD CONSTRAINT fk_creator_master
    FOREIGN KEY (master_id) REFERENCES creator_master(id);
CREATE INDEX idx_creator_master_id ON creator(master_id);

-- Check Constraint mới: chuẩn hóa tuyệt đối theo master_id
ALTER TABLE creator ADD CONSTRAINT ck_creator_authorization CHECK (
    (source_type = 'PUBLIC' AND master_id IS NULL AND authorized_at IS NULL) OR
    (source_type = 'AUTHORIZED' AND master_id IS NOT NULL AND authorized_at IS NOT NULL)
);

-- 3. Bổ sung Provenance cho bảng public_creator_metric
ALTER TABLE public_creator_metric ADD COLUMN IF NOT EXISTS crawl_run_id VARCHAR(64);
ALTER TABLE public_creator_metric ADD COLUMN IF NOT EXISTS observed_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_metric_crawl_run ON public_creator_metric(creator_id, crawl_run_id);

-- 4. Bổ sung Dual Reference (Nullable) cho M1 & Shortlist / Relationship / Collaboration
ALTER TABLE shortlist_item ADD COLUMN master_id UUID;
ALTER TABLE shortlist_item ADD CONSTRAINT fk_shortlist_master
    FOREIGN KEY (master_id) REFERENCES creator_master(id);
CREATE INDEX idx_shortlist_master ON shortlist_item(master_id);

ALTER TABLE relationship ADD COLUMN master_id UUID;
ALTER TABLE relationship ADD CONSTRAINT fk_relationship_master
    FOREIGN KEY (master_id) REFERENCES creator_master(id);
CREATE INDEX idx_relationship_master ON relationship(master_id);

ALTER TABLE collaboration ADD COLUMN master_id UUID;
ALTER TABLE collaboration ADD CONSTRAINT fk_collaboration_master
    FOREIGN KEY (master_id) REFERENCES creator_master(id);
CREATE INDEX idx_collaboration_master ON collaboration(master_id);

ALTER TABLE historical_campaign ADD COLUMN master_id UUID;
ALTER TABLE historical_campaign ADD CONSTRAINT fk_historical_master
    FOREIGN KEY (master_id) REFERENCES creator_master(id);
CREATE INDEX idx_historical_master ON historical_campaign(master_id);

-- LƯU Ý: Tuyệt đối KHÔNG backfill Master ID! Toàn bộ Account cũ giữ master_id = NULL (Unclaimed).
```

#### Tệp `V9__creator_claim_and_oauth_tables.sql`:
```sql
-- D:\SEP_BE\src\main\resources\db\migration\V9__creator_claim_and_oauth_tables.sql

-- 1. Lời mời xác minh hồ sơ (Bổ sung đầy đủ brand_id và invited_by)
CREATE TABLE creator_invitation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_creator_id UUID NOT NULL REFERENCES creator(id),
    campaign_id UUID REFERENCES campaign(id),
    brand_id UUID REFERENCES brand_profile(id),
    invited_by UUID NOT NULL REFERENCES "user"(id),
    invited_email VARCHAR(255) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, ACCEPTED, EXPIRED, CANCELLED
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_invitation_token ON creator_invitation(token_hash);
CREATE INDEX idx_invitation_brand ON creator_invitation(brand_id, status);

-- 2. Lưu định danh trả về từ OAuth Provider (Tránh lệch namespace Provider ID vs Public ID)
CREATE TABLE creator_provider_identity (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    creator_id UUID NOT NULL REFERENCES creator(id),
    provider VARCHAR(50) NOT NULL, -- TIKTOK, YOUTUBE, FACEBOOK, INSTAGRAM
    provider_user_id VARCHAR(255) NOT NULL,
    provider_username VARCHAR(255),
    raw_profile_data JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_provider_identity UNIQUE(provider, provider_user_id)
);
CREATE INDEX idx_provider_identity_creator ON creator_provider_identity(creator_id);

-- 3. Bằng chứng xác minh quyền kiểm soát tài khoản (Token được mã hóa an toàn AES-256-GCM)
CREATE TABLE creator_account_verification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    creator_id UUID NOT NULL REFERENCES creator(id),
    verified_by_user_id UUID NOT NULL REFERENCES "user"(id),
    verification_method VARCHAR(50) NOT NULL DEFAULT 'OAUTH_AUTHORIZATION',
    granted_scopes TEXT,
    access_token_encrypted TEXT,
    refresh_token_encrypted TEXT,
    token_expires_at TIMESTAMP,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, REVOKED, EXPIRED
    verified_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_account_verification_creator ON creator_account_verification(creator_id, status);

-- 4. Bảng xử lý ngoại lệ và xung đột liên kết (Dành cho Data Manager)
CREATE TABLE creator_identity_conflict (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conflict_type VARCHAR(64) NOT NULL, -- ACCOUNT_ALREADY_LINKED, NAMESPACE_MISMATCH, DUPLICATE_CLAIM
    source_account_id UUID REFERENCES creator(id),
    target_master_id UUID REFERENCES creator_master(id),
    conflict_details JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_MANAGER', -- PENDING_MANAGER, RESOLVED, REJECTED
    resolved_by UUID REFERENCES "user"(id),
    resolved_at TIMESTAMP,
    resolution_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 5. Lịch sử kiểm toán danh tính (Audit Trail cho bảo mật và tranh chấp)
CREATE TABLE creator_identity_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(64) NOT NULL, -- CLAIM_MASTER, LINK_ACCOUNT, UNLINK_ACCOUNT, REVOKE_ACCESS
    master_id UUID REFERENCES creator_master(id),
    account_id UUID REFERENCES creator(id),
    performed_by UUID NOT NULL REFERENCES "user"(id),
    details JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

---

### 7.2. Ingestion Pipeline Decoupling & Advisory Lock

- [ ] Cập nhật [CreatorIngestionRequest.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/dto/CreatorIngestionRequest.java):
  ```java
  private Boolean isStableExternalId;
  private String crawlRunId;
  private LocalDateTime observedAt;
  private List<String> bioCrossLinks;
  ```
- [ ] Bổ sung method khóa số nguyên trong [CreatorRepository.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/repository/CreatorRepository.java):
  ```java
  @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:lockKey))", nativeQuery = true)
  void acquireIdentityLock(@Param("lockKey") String lockKey);
  ```
- [ ] Cập nhật [CreatorIngestionService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorIngestionService.java):
  - Áp dụng Advisory Lock: `creatorRepository.acquireIdentityLock(request.getPlatform() + ":" + request.getExternalId());`
  - **Tạo Account mới khi chưa cào**:
    ```java
    Creator creator = Creator.builder()
        .master(null) // LUÔN LÀ NULL, KHÔNG TỰ Ý TẠO MASTER
        .sourceType(CreatorSourceType.PUBLIC)
        .platform(request.getPlatform())
        .externalId(request.getExternalId())
        .isStableId(request.getIsStableExternalId() != null ? request.getIsStableExternalId() : true)
        .userName(request.getUserName())
        .displayName(request.getDisplayName())
        .avatarUrl(request.getAvatarUrl())
        .profileUrl(request.getProfileUrl())
        .location(request.getLocation())
        .build();
    creatorRepository.save(creator);
    ```
  - **Snapshot Idempotency**: Nếu `crawlRunId` đã tồn tại trên metric của kênh thì bỏ qua ghi log snapshot trùng.

---

### 7.3. Discovery Hybrid Grouping Query (Fixed PostgreSQL DISTINCT ON)

- [ ] Bổ sung vào interface [HybridDiscoveryCreatorProjection.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/discovery/dto/HybridDiscoveryCreatorProjection.java):
  ```java
  UUID getMasterId();
  String getClaimStatus();
  ```
- [ ] Thay thế query trong [PublicCreatorMetricRepository.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/repository/PublicCreatorMetricRepository.java):
```sql
@Query(
    value = """
        SELECT * FROM (
            SELECT DISTINCT ON (COALESCE('MASTER:' || c.master_id::text, 'ACCOUNT:' || c.id::text))
                m.id AS id,
                c.id AS creatorId,
                c.master_id AS masterId,
                COALESCE(cm.canonical_name, c.display_name, c.user_name) AS displayName,
                c.platform AS platform,
                c.user_name AS userName,
                c.avatar_url AS avatarUrl,
                c.profile_url AS profileUrl,
                m.followers AS followers,
                m.avg_views AS avgViews,
                m.avg_likes AS avgLikes,
                m.niche AS niche,
                m.category AS category,
                m.engagement_rate AS engagementRate,
                m.content_summary AS contentSummary,
                CASE WHEN c.master_id IS NOT NULL THEN 'LINKED_PROFILE' ELSE 'PUBLIC_PROFILE' END AS claimStatus,
                CASE
                    WHEN :hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL
                    THEN (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector)))
                    ELSE 0.0
                END AS semanticScore,
                CASE
                    WHEN :hasKeyword = true AND :formattedKeyword IS NOT NULL AND :formattedKeyword != ''
                    THEN ts_rank(m.search_vector, to_tsquery('simple', :formattedKeyword))
                    ELSE 0.0
                END AS ftsScore,
                CASE
                    WHEN :hasKeyword = true THEN (
                        (CASE WHEN :hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL THEN (0.6 * (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector)))) ELSE 0.0 END)
                      + (CASE WHEN :formattedKeyword IS NOT NULL AND :formattedKeyword != '' THEN (0.4 * ts_rank(m.search_vector, to_tsquery('simple', :formattedKeyword))) ELSE 0.0 END)
                    )
                    ELSE 0.0
                END AS hybridScore
            FROM public_creator_metric m
            JOIN creator c ON m.creator_id = c.id
            LEFT JOIN creator_master cm ON c.master_id = cm.id
            WHERE
                (:hasPlatforms = false OR c.platform IN (:platforms))
                AND (:hasNiches = false OR m.niche IN (:niches))
                AND (:uiNiche IS NULL OR m.niche = :uiNiche)
                AND (:followerMin IS NULL OR m.followers >= :followerMin)
                AND (:followerMax IS NULL OR m.followers <= :followerMax)
                AND (
                    :hasKeyword = false
                    OR (:hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL AND (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector))) > 0.3)
                    OR (:formattedKeyword IS NOT NULL AND :formattedKeyword != '' AND m.search_vector @@ to_tsquery('simple', :formattedKeyword))
                )
            ORDER BY
                COALESCE('MASTER:' || c.master_id::text, 'ACCOUNT:' || c.id::text),
                CASE
                    WHEN :hasKeyword = true THEN (
                        (CASE WHEN :hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL THEN (0.6 * (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector)))) ELSE 0.0 END)
                      + (CASE WHEN :formattedKeyword IS NOT NULL AND :formattedKeyword != '' THEN (0.4 * ts_rank(m.search_vector, to_tsquery('simple', :formattedKeyword))) ELSE 0.0 END)
                    )
                    ELSE 0.0
                END DESC,
                m.followers DESC
        ) ranked_creators
        ORDER BY
            ranked_creators.hybridScore DESC,
            ranked_creators.followers DESC
        """,
    countQuery = """
        SELECT count(DISTINCT COALESCE('MASTER:' || c.master_id::text, 'ACCOUNT:' || c.id::text))
        FROM public_creator_metric m
        JOIN creator c ON m.creator_id = c.id
        WHERE
            (:hasPlatforms = false OR c.platform IN (:platforms))
            AND (:hasNiches = false OR m.niche IN (:niches))
            AND (:uiNiche IS NULL OR m.niche = :uiNiche)
            AND (:followerMin IS NULL OR m.followers >= :followerMin)
            AND (:followerMax IS NULL OR m.followers <= :followerMax)
            AND (
                :hasKeyword = false
                OR (:hasVector = true AND :queryVector IS NOT NULL AND c.embedding IS NOT NULL AND (1 - (c.embedding <=> cast(NULLIF(:queryVector, '') as vector))) > 0.3)
                OR (:formattedKeyword IS NOT NULL AND :formattedKeyword != '' AND m.search_vector @@ to_tsquery('simple', :formattedKeyword))
            )
        """,
    nativeQuery = true
)
Page<HybridDiscoveryCreatorProjection> searchHybridCreators(...);
```

---

### 7.4. Invitation & Profile Claim Engine

- [ ] Tạo mới Service `CreatorInvitationService.java`:
  - `sendInvitation(UUID creatorId, String email, UUID brandId, UUID campaignId)`:
    - Băm token bằng SHA-256 lưu `token_hash`.
    - Tạo lời mời thời hạn 7 ngày (`expires_at = now + 7 days`).
    - Gửi email kèm link `/claim-profile?token=RAW_TOKEN`.
  - `verifyInvitationToken(String rawToken)`:
    - Băm `rawToken` tìm kiếm trong bảng `creator_invitation`.
    - Kiểm tra `status == 'PENDING'` và `expires_at > now`.
    - Trả về thông tin kênh mục tiêu cần xác minh (`platform`, `userName`, `avatarUrl`).

---

### 7.5. OAuth Verification & Atomic Master Creation

- [ ] Tạo mới Controller `CreatorClaimOAuthController.java`:
  - `GET /api/v1/creators/claim/oauth/{platform}/start?token=RAW_TOKEN`: Chuyển hướng sang OAuth của nền tảng tương ứng.
  - `GET /api/v1/creators/claim/oauth/{platform}/callback?code=...&state=...`: Tiếp nhận Authorization Code.
- [ ] Tạo mới Service `CreatorClaimResolutionService.java`:
  - **Kiểm tra khớp định danh Proof of Control**:
    - *YouTube*: Gọi Google API `/youtube/v3/channels?mine=true` lấy `channel.id` so sánh với `creator.external_id`.
    - *TikTok*: Gọi `/v2/user/info/` lấy `username` chuẩn hóa đối chiếu với `creator.user_name`.
  - **Thực thi tạo Master lần đầu trong 1 Transaction (`@Transactional`)**:
    1. Lock tài khoản A001 (`PESSIMISTIC_WRITE`).
    2. Nếu `A001.master_id != null` ➔ Đã bị claim ➔ Ném `ConflictException`.
    3. Tìm hoặc tạo mới `CreatorMaster` M001 gắn với `claimantUser` (`user_id = claimantUser.getId()`).
    4. Cập nhật `A001`:
       - `A001.setMaster(M001)`
       - `A001.setSourceType(CreatorSourceType.AUTHORIZED)`
       - `A001.setAuthorizedAt(LocalDateTime.now())`
    5. Lưu bản ghi `creator_account_verification` (token đã mã hóa AES-256-GCM).
    6. Lưu bản ghi `creator_provider_identity`.
    7. Đánh dấu `creator_invitation.status = 'ACCEPTED'`.
    8. Ghi log `creator_identity_audit`.

---

### 7.6. Connect Additional Accounts Service (4 Scenarios)

- [ ] Tạo mới Service `CreatorAccountLinkService.java`:
  - KOC đăng nhập Portal và kết nối kênh thứ 2, 3:
  - Lấy `CreatorMaster` hiện tại của KOC: `creatorMasterRepository.findByUserId(currentUser.getId())`.
  - **Triển khai 4 kịch bản liên kết tài khoản**:
    - **Case 1 (Tài khoản đã có trong DB qua crawler, `master_id = NULL`)**:
      - Tái sử dụng bản ghi A002 hiện có cùng toàn bộ metrics và bài đăng lịch sử.
      - Gán: `A002.setMaster(currentMaster)`, `A002.setSourceType(AUTHORIZED)`, `A002.setAuthorizedAt(now)`.
      - Lưu verification và audit.
    - **Case 2 (Tài khoản hoàn toàn mới chưa từng cào)**:
      - Tạo bản ghi mới A003 gán trực tiếp `master_id = currentMaster`, `sourceType = AUTHORIZED`.
    - **Case 3 (Tài khoản đã thuộc chính Master M001)**:
      - Cập nhật hạn OAuth token và báo thành công.
    - **Case 4 (Tài khoản đã thuộc Master M002 khác)**:
      - **Tuyệt đối không tự ý đổi chủ**. Ghi bản ghi vào `creator_identity_conflict` (loại `ACCOUNT_ALREADY_LINKED`) để Data Manager xử lý.

---

### 7.7. Dual References & CRM/Shortlist Adaptation

- [ ] Cập nhật [ShortlistItem.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/model/ShortlistItem.java) & [BrandCreatorRelationship.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/model/BrandCreatorRelationship.java):
  - Thêm trường:
    ```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_id")
    private CreatorMaster master;
    ```
  - Giữ nguyên unique constraint `ux_shortlist_campaign_creator` trên `(campaign_id, creator_id)` để hỗ trợ Shortlist từng kênh cụ thể.
  - Khi KOC claim thành công: Tự động cập nhật `master_id = M001` cho toàn bộ bản ghi Shortlist/Relationship liên quan.

---

### 7.8. Data Manager Conflict Resolution

- [ ] Tạo Controller `AdminIdentityConflictController.java`:
  - `GET /api/v1/admin/creator-conflicts`: Danh sách tranh chấp hoặc kênh bị claim đè.
  - `POST /api/v1/admin/creator-conflicts/{id}/resolve`: Quyết định phê duyệt chuyển kênh (`APPROVE_TRANSFER`), từ chối (`REJECT`) hoặc yêu cầu bổ sung bằng chứng.

---

## 8. GIẢI QUYẾT BẪY KỸ THUẬT (TECHNICAL GOTCHAS & CRITICAL TRAPS)

1. **Bẫy 1: Sắp xếp kết quả sai khi dùng PostgreSQL `DISTINCT ON`**:
   - *Nguyên nhân*: PostgreSQL bắt buộc `ORDER BY` của `DISTINCT ON` phải bắt đầu bằng cột gom nhóm. Nếu viết trực tiếp, kết quả trả về sẽ bị sắp xếp theo UUID thay vì theo điểm số.
   - *Khắc phục*: Bắt buộc bao bọc bằng **Subquery / CTE** và thực hiện `ORDER BY ranked_creators.hybridScore DESC` ở vòng ngoài cùng.
2. **Bẫy 2: Lỗi tham số String trong PostgreSQL Advisory Lock**:
   - *Nguyên nhân*: `pg_advisory_xact_lock` không nhận chuỗi `VARCHAR`.
   - *Khắc phục*: Dùng hàm băm tích hợp của PostgreSQL `SELECT pg_advisory_xact_lock(hashtext(:lockKey))` để chuyển chuỗi `platform:externalId` thành số nguyên 32/64-bit.
3. **Bẫy 3: Vi phạm Check Constraint `ck_creator_authorization`**:
   - *Nguyên nhân*: Xóa bỏ `owner_user_id` thì constraint cũ sẽ lỗi.
   - *Khắc phục*: Cập nhật constraint trong V8 chỉ dựa trên `master_id`:
     `CHECK ((source_type = 'PUBLIC' AND master_id IS NULL AND authorized_at IS NULL) OR (source_type = 'AUTHORIZED' AND master_id IS NOT NULL AND authorized_at IS NOT NULL))`
4. **Bẫy 4: Namespace Mismatch giữa OAuth Provider ID và Crawler Public ID**:
   - *Nguyên nhân*: TikTok OAuth trả về `open_id` băm theo app, crawler lưu `user_id` số.
   - *Khắc phục*: Gọi API `/v2/user/info/` bóc tách `username` chuẩn hóa để đối chiếu an toàn.

---

## 9. BẢNG KIỂM THỬ NGHIỆM THU (ACCEPTANCE TESTING MATRIX - 18 TCS)

| Mã TC | Kịch bản Kiểm thử | Thao tác Kỹ thuật | Kết quả Nghiệm thu Kỳ vọng |
| :---: | :--- | :--- | :--- |
| **TC-01** | Cào KOC mới ngoài mạng xã hội | Ingest TikTok `@linh.beauty`. | Tạo `creator` với `master_id = NULL`. Bảng `creator_master` không sinh bản ghi. |
| **TC-02** | Cào lại cùng Stable ID | Ingest lại cùng `external_id`. | Cập nhật metadata, giữ nguyên `creator.id`, không sinh duplicate. |
| **TC-03** | KOC chưa claim tham gia Discovery | Brand tìm kiếm KOC trên UI. | Hiển thị bình thường với nhãn `Public Profile`. |
| **TC-04** | Gửi lời mời Claim hồ sơ | Brand tạo lời mời tới email KOC. | Sinh bản ghi `creator_invitation` (token hash SHA-256), chưa tạo Master. |
| **TC-05** | Recipient đăng nhập OAuth sai kênh | KOC đăng nhập tài khoản TikTok khác với tài khoản được mời. | Báo lỗi `IdentityMismatchException`, từ chối tạo Master. |
| **TC-06** | OAuth đúng tài khoản được mời | KOC đăng nhập đúng tài khoản TikTok A001. | Tạo Master M001 (gắn User KOC), gán `A001.master_id = M001`, lưu verification audit. |
| **TC-07** | Hai Claim Sessions chạy đồng thời | 2 tab trình duyệt cùng submit OAuth callback cho A001. | Row lock chặn lại: 1 Master duy nhất được tạo, phiên thứ 2 báo đã hoàn tất. |
| **TC-08** | Kết nối kênh thứ 2 đã có sẵn trong DB | KOC vào Portal OAuth YouTube A002 (đã cào trước đó). | Tái sử dụng A002, gán `A002.master_id = M001`. Không tạo Account trùng. |
| **TC-09** | Kết nối kênh thứ 2 chưa có trong DB | KOC OAuth kênh Instagram A003 hoàn toàn mới. | Tạo bản ghi `creator` mới gán `master_id = M001`. |
| **TC-10** | OAuth ID khác namespace Public ID | TikTok trả về `open_id`. | Gọi `/v2/user/info/` bóc tách username để ánh xạ an toàn. |
| **TC-11** | Kết nối kênh đã thuộc Master khác | KOC muốn liên kết kênh đã thuộc M002. | Ghi vào `creator_identity_conflict`, không tự ý chuyển quyền. |
| **TC-12** | KOC sở hữu 2 kênh đã xác minh | Xem kết quả Discovery. | Hiển thị 1 Creator Card duy nhất đại diện cho Master, kênh điểm cao nhất đại diện. |
| **TC-13** | Hai KOC chưa claim có `master_id = NULL` | Xem danh sách Discovery. | Mỗi KOC hiển thị thành 1 Card độc lập, không bị gom chung. |
| **TC-14** | Claim sau khi đã có Shortlist/Hợp đồng | A001 đã có trong Campaign của Brand trước khi claim. | Sau khi claim thành công, các bản ghi tự động cập nhật `master_id = M001`. |
| **TC-15** | OAuth Access Token hết hạn | Token YouTube hết hạn sau 1 giờ. | Cập nhật quyền cần gia hạn, không xóa Account, Master hay dữ liệu M2. |
| **TC-16** | Data Manager xử lý tranh chấp kênh | Data Manager duyệt chuyển kênh trên Admin UI. | Chuyển `master_id` có audit log đầy đủ kèm lý do duyệt. |
| **TC-17** | Trình duyệt retry callback OAuth | Bấm refresh trang callback. | Không tạo trùng Master hay bản ghi verification. |
| **TC-18** | Shortlist cả 2 kênh trước khi KOC claim | Campaign shortlist cả TikTok và YouTube của KOC. | Không văng lỗi unique constraint sau khi KOC claim thành công. |

---

## 10. LỘ TRÌNH TRIỂN KHAI REVIEW 2 (3-WEEK IMPLEMENTATION ROADMAP)

```
Tuần 1: Nền Tảng & Decoupling Ingestion (P0)
├── Gộp migration cơ sở (V1..V7) và chạy Flyway V8 (bảng creator_master, xóa creator.owner_user_id, thêm master_id, sửa constraint).
├── Tái cấu trúc package security/ thành các folder con (jwt, oauth, permission, apikey, evaluator).
├── Sửa CreatorIngestionService (áp dụng pg_advisory_xact_lock(hashtext), gán master_id = null).
└── Cập nhật Discovery Native Query (triển khai Subquery DISTINCT ON xếp hạng theo hybridScore).

Tuần 2: Lời Mời & Claim Kênh Đầu Tiên (P0)
├── Chạy Flyway V9 (các bảng invitation, verification, provider_identity, conflict, audit).
├── Triển khai CreatorInvitationService (tạo lời mời gắn brand_id, gửi email, hash token SHA-256).
├── Triển khai Google YouTube OAuth Callback & Namespace Resolution.
└── Triển khai CreatorClaimResolutionService (tạo Master M001 nguyên tử lần đầu gắn với User KOC).

Tuần 3: Kết Nối Kênh Thứ Hai & Demo Xuyên Suốt (P1)
├── Triển khai CreatorAccountLinkService (kết nối kênh thứ 2, tái sử dụng account đã cào, xử lý 4 kịch bản).
├── Cập nhật màn hình Creator Portal cho phép bấm "Connect Another Channel".
└── Thực hiện kịch bản Demo hoàn chỉnh cho Hội đồng Review 2:
    Crawl 2 kênh -> Discovery -> Shortlist -> Gửi lời mời -> KOC OAuth kênh 1 -> Tạo Master -> Kết nối kênh 2 -> Master Grouping hoàn chỉnh.
```

---

## 11. CHECKLIST TRIỂN KHAI CHI TIẾT THEO TỪNG PHASE LẬP TRÌNH (STEP-BY-STEP CODING PHASES)

### 🚀 PHASE 1: Database Migration & Schema Consolidation (Foundation)
> **Mục tiêu**: Chuẩn hóa toàn bộ schema dưới V10 (từ V1 đến V9), xóa bỏ hoàn toàn `owner_user_id`, tạo `creator_master` và các bảng OAuth Claim.

- [x] **1.1. Hoàn thiện migration cơ sở V8** tại [V8__claim_based_identity_foundation.sql](file:///d:/SEP_BE/src/main/resources/db/migration/V8__claim_based_identity_foundation.sql):
  - [x] Tạo bảng `creator_master(id, user_id, canonical_name, status, notes, created_at, updated_at)`.
  - [x] Khóa ngoại `user_id UNIQUE REFERENCES "user"(id)`.
  - [x] Drop check constraint cũ `ck_creator_authorization` và drop cột `owner_user_id` trên bảng `creator`.
  - [x] Thêm cột `master_id UUID REFERENCES creator_master(id)` và `is_stable_id BOOLEAN DEFAULT TRUE` vào bảng `creator`.
  - [x] Tạo index `idx_creator_master_id ON creator(master_id)`.
  - [x] Tạo check constraint mới `ck_creator_authorization`:
    `CHECK ((source_type = 'PUBLIC' AND master_id IS NULL AND authorized_at IS NULL) OR (source_type = 'AUTHORIZED' AND master_id IS NOT NULL AND authorized_at IS NOT NULL))`.
  - [x] Thêm cột provenance `crawl_run_id VARCHAR(64)` và `observed_at TIMESTAMP` vào `public_creator_metric`.
  - [x] Thêm Dual Reference `master_id UUID` vào các bảng `shortlist_item`, `relationship`, `collaboration`, `historical_campaign`.
- [x] **1.2. Hoàn thiện migration bảng Claim V9** tại [V9__creator_claim_and_oauth_tables.sql](file:///d:/SEP_BE/src/main/resources/db/migration/V9__creator_claim_and_oauth_tables.sql):
  - [x] Tạo bảng `creator_invitation` (kèm `brand_id UUID REFERENCES brand_profile(id)` và `invited_by UUID REFERENCES "user"(id)`).
  - [x] Tạo bảng `creator_provider_identity` lưu ánh xạ Provider ID / open_id với `creator(id)`.
  - [x] Tạo bảng `creator_account_verification` lưu access/refresh token mã hóa.
  - [x] Tạo bảng `creator_identity_conflict` lưu tranh chấp kênh cho Data Manager.
  - [x] Tạo bảng `creator_identity_audit` ghi nhật ký kiểm toán danh tính.
- [x] **1.3. Xác thực Migration**:
  - Chạy lệnh: `mvn clean compile -DskipTests` để xác nhận Flyway load thành công file SQL.
  - **DoD**: Flyway validate 9 file migration (V1 -> V9) không phát sinh lỗi versioning.

---

### 🧩 PHASE 2: Domain Entities & Repositories (Java Layer)
> **Mục tiêu**: Tạo Entity `CreatorMaster`, cập nhật `Creator`, bổ sung 5 Entity mới kế thừa `BaseEntity` và cập nhật các Repository.

- [x] **2.1. Cập nhật Entity [Creator.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/model/Creator.java)**:
  - [x] Xóa trường `private User owner;`.
  - [x] Thêm quan hệ `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "master_id") private CreatorMaster master;`.
  - [x] Thêm trường `@Builder.Default @Column(name = "is_stable_id", nullable = false) private Boolean isStableId = true;`.
  - [x] Thêm helper method tương thích ngược:
    ```java
    public User getOwner() { return master != null ? master.getUser() : null; }
    ```
- [x] **2.2. Tạo mới Entity [CreatorMaster.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/model/CreatorMaster.java)**:
  - [x] Kế thừa [BaseEntity.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/common/BaseEntity.java) với annotation `@SuperBuilder`.
  - [x] `@OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", unique = true, nullable = false) private User user;`.
  - [x] `@Column(name = "canonical_name", nullable = false) private String canonicalName;`.
  - [x] `@Column(nullable = false, length = 32) private String status;` (ACTIVE, SUSPENDED).
  - [x] `@OneToMany(mappedBy = "master") private List<Creator> accounts;`.
- [x] **2.3. Tạo 5 Entity mới** trong `com.influencermatch.backend.creator.model` (kế thừa `BaseEntity`):
  - [x] `CreatorInvitation.java` (`targetCreator`, `campaign`, `brand`, `invitedBy`, `invitedEmail`, `tokenHash`, `status`, `expiresAt`).
  - [x] `CreatorProviderIdentity.java` (`creator`, `provider`, `providerUserId`, `providerUsername`, `rawProfileData`).
  - [x] `CreatorAccountVerification.java` (`creator`, `verifiedByUser`, `verificationMethod`, `grantedScopes`, `accessTokenEncrypted`, `refreshTokenEncrypted`, `tokenExpiresAt`, `status`, `verifiedAt`).
  - [x] `CreatorIdentityConflict.java` (`conflictType`, `sourceAccount`, `targetMaster`, `conflictDetails`, `status`, `resolvedBy`, `resolvedAt`, `resolutionNotes`).
  - [x] `CreatorIdentityAudit.java` (`eventType`, `master`, `account`, `performedBy`, `details`).
- [x] **2.4. Cập nhật Dual References trong các Entity liên quan**:
  - [x] [ShortlistItem.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/model/ShortlistItem.java): Thêm `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "master_id") private CreatorMaster master;`.
  - [x] [BrandCreatorRelationship.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/relationship/model/BrandCreatorRelationship.java): Thêm `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "master_id") private CreatorMaster master;`.
  - [x] [Collaboration.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/collaboration/model/Collaboration.java): Thêm `master`.
- [x] **2.5. Tạo và cập nhật Repositories**:
  - [x] Tạo `CreatorMasterRepository.java` với `Optional<CreatorMaster> findByUserId(UUID userId);`.
  - [x] Tạo `CreatorInvitationRepository.java` với `Optional<CreatorInvitation> findByTokenHash(String tokenHash);` và `boolean existsByTargetCreatorIdAndStatus(UUID creatorId, String status);`.
  - [x] Tạo `CreatorProviderIdentityRepository.java` với `Optional<CreatorProviderIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);`.
  - [x] Tạo `CreatorAccountVerificationRepository.java`, `CreatorIdentityConflictRepository.java`, `CreatorIdentityAuditRepository.java`.
  - [x] Cập nhật [CreatorRepository.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/repository/CreatorRepository.java):
    - [x] Thêm method khóa Advisory Lock:
      ```java
      @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:lockKey))", nativeQuery = true)
      void acquireIdentityLock(@Param("lockKey") String lockKey);
      ```
    - [x] Thêm `List<Creator> findByMasterId(UUID masterId);`.
    - [x] Thay thế `existsByOwnerId` thành `boolean existsByMasterUserId(UUID userId);`.
- [x] **DoD**: Dự án compile sạch sẽ (`mvn test-compile -DskipTests`), schema JPA validate khớp hoàn toàn với Flyway.

---

### 🔒 PHASE 3: Modularized Security & Token Encryption
> **Mục tiêu**: Tái cấu trúc package `security/` thành các folder chuyên biệt và xây dựng Service mã hóa OAuth Token.

- [x] **3.1. Tạo các sub-package cấu trúc mới** trong `com.influencermatch.backend.security`:
  - `security.jwt`, `security.oauth`, `security.apikey`, `security.evaluator`, `security.permission`, `security.service`.
- [x] **3.2. Di chuyển các class hiện tại vào sub-package tương ứng** và cập nhật import:
  - [x] Chuyển `JwtTokenProvider`, `JwtAuthenticationFilter`, `JwtAuthenticationEntryPoint`, `JwtAccessDeniedHandler` vào `security.jwt`.
  - [x] Chuyển `GoogleLoginIntent`, `GoogleOAuthSuccessHandler`, `GoogleOAuthFailureHandler` vào `security.oauth`.
  - [x] Chuyển `CreatorServiceKeyFilter` vào `security.apikey`.
  - [x] Chuyển `Permission`, `Permissions`, `RolePermissions`, sub-package `role/` vào `security.permission`.
  - [x] Chuyển `UserDetailsServiceImpl` vào `security.service`.
- [x] **3.3. Xây dựng `OAuthTokenEncryptionService.java`** trong `security.oauth`:
  - [x] Thuật toán: AES-256-GCM với IV ngẫu nhiên 12-byte và Auth Tag 128-bit.
  - [x] Cấu hình key qua `application.security.oauth-token.secret-key` trong `application.yml`.
  - [x] Cung cấp 2 method: `String encrypt(String rawToken)` và `String decrypt(String encryptedToken)`.
- [x] **3.4. Xây dựng `CreatorSecurityEvaluator.java`** trong `security.evaluator`:
  - [x] Định nghĩa bean `@Component("creatorSecurity")`.
  - [x] Cung cấp method `boolean isOwner(UUID creatorId)`: kiểm tra xem `creator.getMaster().getUser().getId()` có khớp với `Permissions.actor().getId()` không.
- [x] **3.5. Cập nhật [SecurityConfig.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/config/SecurityConfig.java)**:
  - [x] Cập nhật toàn bộ import theo package mới.
  - [x] Mở public endpoint: `/creators/claim/invitation/verify`, `/creators/claim/oauth/**`.
- [x] **DoD**: Toàn bộ unit tests bảo mật `GoogleAuthServiceTest`, `AuthControllerTest`, `RolePermissionsTest` pass 100%.

---

### 🌐 PHASE 4: Ingestion Decoupling & Discovery Hybrid Grouping
> **Mục tiêu**: Đảm bảo crawler chỉ cào tài khoản độc lập (`master_id = NULL`), chống xung đột cào đồng thời và sửa lỗi sắp xếp query Discovery.

- [x] **4.1. Cập nhật DTO [CreatorIngestionRequest.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/dto/CreatorIngestionRequest.java)**:
  - [x] Thêm các trường: `Boolean isStableExternalId`, `String crawlRunId`, `LocalDateTime observedAt`, `List<String> bioCrossLinks`.
- [x] **4.2. Cập nhật [CreatorIngestionService.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/service/CreatorIngestionService.java)**:
  - [x] Gọi khóa Advisory Lock: `creatorRepository.acquireIdentityLock(request.getPlatform() + ":" + request.getExternalId());`.
  - [x] Khi tạo mới `Creator`: luôn gán `.master(null)` và `.sourceType(CreatorSourceType.PUBLIC)`.
  - [x] Khi cập nhật `Creator` đã có: giữ nguyên `master_id` và `sourceType` hiện tại (không ghi đè trạng thái đã claim).
  - [x] Snapshot Idempotency: Kiểm tra nếu `(creator.id, request.getCrawlRunId())` đã tồn tại trên metric thì bỏ qua ghi log snapshot trùng lặp.
- [x] **4.3. Cập nhật interface [HybridDiscoveryCreatorProjection.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/discovery/dto/HybridDiscoveryCreatorProjection.java)**:
  - [x] Bổ sung getter: `UUID getMasterId();` và `String getClaimStatus();`.
- [x] **4.4. Thay thế native query trong [PublicCreatorMetricRepository.java](file:///d:/SEP_BE/src/main/java/com/influencermatch/backend/creator/repository/PublicCreatorMetricRepository.java)**:
  - [x] Triển khai Subquery bọc `DISTINCT ON (COALESCE('MASTER:' || c.master_id::text, 'ACCOUNT:' || c.id::text))`.
  - [x] Sắp xếp vòng ngoài: `ORDER BY ranked_creators.hybridScore DESC, ranked_creators.followers DESC`.
  - [x] Viết `countQuery`: `SELECT count(DISTINCT COALESCE('MASTER:' || c.master_id::text, 'ACCOUNT:' || c.id::text)) FROM public_creator_metric m JOIN creator c ON m.creator_id = c.id WHERE ...`.
- [x] **4.5. Cập nhật `creator-discovery` (Python)**:
  - [x] Đánh dấu `isStableExternalId = False` trong [facebook_crawler.py](file:///d:/creator-discovery/app/crawlers/facebook_crawler.py).
  - [x] Cập nhật [java_mapper.py](file:///d:/creator-discovery/app/mappers/java_mapper.py) map đủ các trường provenance.
- [x] **DoD**:
  - Ingest cào tài khoản mới tạo `creator` với `master_id = null`.
  - Gọi API `/discovery` hiển thị KOC chưa claim độc lập, KOC đã có chung `master_id` được gom nhóm lại thành 1 thẻ duy nhất và xếp đúng theo điểm `hybridScore DESC`.

---

### 📩 PHASE 5: Invitation Engine & Profile Claim Flow (Kênh 1 OAuth)
> **Mục tiêu**: Brand gửi lời mời claim kênh, KOC đăng nhập OAuth kênh đầu tiên để xác thực Proof of Control và tạo `CreatorMaster` nguyên tử.

- [x] **5.1. Tạo DTOs cho lời mời và Claim** trong `com.influencermatch.backend.creator.dto`:
  - [x] `SendInvitationRequest(String email, UUID campaignId)`
  - [x] `InvitationResponse(UUID id, UUID creatorId, String email, String status, LocalDateTime expiresAt)`
  - [x] `VerifyTokenResponse(UUID creatorId, String platform, String userName, String displayName, String avatarUrl)`
  - [x] `OAuthCallbackResponse(UUID masterId, UUID creatorId, String canonicalName, String status)`
- [x] **5.2. Triển khai Service `CreatorInvitationService.java`**:
  - [x] `sendInvitation(UUID creatorId, String email, UUID brandId, UUID campaignId)`:
    - [x] Sinh token ngẫu nhiên 32-byte bằng `SecureRandom`, băm SHA-256 lưu `token_hash`.
    - [x] Hạn dùng 7 ngày (`expires_at = NOW() + 7 days`).
    - [x] Gửi email mời tới KOC kèm link `/claim-profile?token=RAW_TOKEN`.
  - [x] `verifyInvitationToken(String rawToken)`: Băm SHA-256 tìm kiếm và kiểm tra hạn dùng.
- [x] **5.3. Tạo Controller `CreatorInvitationController.java`**:
  - [x] `POST /api/v1/creators/{id}/invite`: Gửi lời mời (yêu cầu quyền `MANAGE_OWN_SHORTLIST`).
  - [x] `GET /api/v1/creators/claim/invitation/verify?token=...`: Public API cho KOC kiểm tra lời mời.
- [x] **5.4. Tạo Controller `CreatorClaimOAuthController.java`**:
  - [x] `GET /api/v1/creators/claim/oauth/{platform}/start?token=...`: Lưu nonce/state chống CSRF, redirect sang OAuth provider.
  - [x] `GET /api/v1/creators/claim/oauth/{platform}/callback?code=...&state=...`: Nhận callback.
- [x] **5.5. Triển khai Service `CreatorClaimResolutionService.java`**:
  - [x] Đổi authorization code lấy access token từ Google/TikTok.
  - [x] Kiểm tra Proof of Control:
    - *YouTube*: Gọi Google API `/youtube/v3/channels?mine=true` lấy `channel.id` so sánh với `creator.external_id`.
    - *TikTok*: Gọi `/v2/user/info/` lấy `username` chuẩn hóa so sánh với `creator.user_name`.
    - Nếu không khớp ➔ ném `IdentityMismatchException`.
  - [x] **Thực hiện Atomic Transaction (`@Transactional`)**:
    1. Khóa tài khoản A001 (`findLockedById` - `PESSIMISTIC_WRITE`).
    2. Kiểm tra `A001.getMaster() == null` (chưa bị ai claim trước đó).
    3. Tìm hoặc tạo mới `CreatorMaster` M1 cho `claimantUser` (`creatorMasterRepository.findByUserId(...)`).
    4. Cập nhật `A001.setMaster(M1)`, `A001.setSourceType(AUTHORIZED)`, `A001.setAuthorizedAt(NOW())`.
    5. Mã hóa token và lưu bản ghi `creator_account_verification`.
    6. Lưu bản ghi `creator_provider_identity`.
    7. Đánh dấu `creator_invitation.status = 'ACCEPTED'`.
    8. Ghi log `creator_identity_audit`.
    9. Tự động cập nhật `master_id = M1` cho toàn bộ bản ghi `shortlist_item`, `relationship` liên quan đến A001.
- [x] **DoD**: Mô phỏng thành công luồng: Gửi mời -> Nhận token -> OAuth Callback -> Kênh chuyển sang `AUTHORIZED`, Master M1 được tạo và gán cho User.

---

### 🔗 PHASE 6: Multi-channel Linking & Conflict Resolution (Kênh 2+ OAuth)
> **Mục tiêu**: KOC đã có Master M1 vào Portal kết nối kênh thứ 2, 3 qua OAuth và xử lý xung đột tranh chấp kênh.

- [x] **6.1. Triển khai Service `CreatorAccountLinkService.java`**:
  - [x] Phương thức `linkAdditionalAccount(UUID userId, String platform, String authorizationCode)`:
  - [x] Lấy Master hiện tại của KOC: `CreatorMaster master = creatorMasterRepository.findByUserId(userId)`.
  - [x] Xác thực Proof of Control lấy `externalId` và `username` từ OAuth Provider.
  - [x] **Xử lý 4 kịch bản liên kết**:
    - [x] **Case 1 (Tài khoản đã có trong DB qua crawler, `master_id = null`)**:
      - Tái sử dụng bản ghi A002 hiện có.
      - Gán `A002.setMaster(master)`, `A002.setSourceType(AUTHORIZED)`, `A002.setAuthorizedAt(NOW())`.
      - Lưu verification token và ghi audit log.
    - [x] **Case 2 (Tài khoản mới chưa từng cào)**:
      - Tạo bản ghi mới A003, gán ngay `master_id = master`, `source_type = AUTHORIZED`.
    - [x] **Case 3 (Tài khoản đã thuộc chính Master M1)**:
      - Cập nhật access/refresh token trong `creator_account_verification` và trả về thành công.
    - [x] **Case 4 (Tài khoản đã thuộc Master M2 khác - Xung đột)**:
      - **Tuyệt đối không tự ý đổi chủ**. Ghi bản ghi vào `creator_identity_conflict` (loại `ACCOUNT_ALREADY_LINKED`, status `PENDING_MANAGER`).
      - Báo cho KOC biết yêu cầu đã được chuyển tới Data Manager xét duyệt tranh chấp.
- [x] **6.2. Tạo Controller `CreatorPortalAccountController.java`**:
  - [x] `POST /api/v1/creators/portal/accounts/connect/{platform}/start`: Bắt đầu kết nối kênh thứ 2.
  - [x] `POST /api/v1/creators/portal/accounts/connect/{platform}/callback`: Hoàn tất liên kết kênh thứ 2.
  - [x] `GET /api/v1/creators/portal/accounts`: Danh sách các kênh mạng xã hội đã liên kết vào Master của KOC.
- [x] **6.3. Tạo Controller `AdminIdentityConflictController.java`**:
  - [x] `GET /api/v1/admin/creator-conflicts`: Danh sách tranh chấp kênh (Data Manager / Admin).
  - [x] `POST /api/v1/admin/creator-conflicts/{id}/resolve`: Quyết định phê duyệt chuyển kênh (`APPROVE_TRANSFER`), từ chối (`REJECT`) hoặc yêu cầu bổ sung bằng chứng.
- [x] **DoD**:
  - KOC kết nối thành công kênh thứ 2, bảng `creator` hiển thị cả 2 kênh cùng chung `master_id = M1`.
  - Trường hợp kết nối kênh đã có chủ sinh bản ghi conflict chờ Admin duyệt.

---

### 🧪 PHASE 7: Automated Integration Testing & Verification (18 Test Cases)
> **Mục tiêu**: Viết bộ integration tests tự động bao phủ trọn vẹn 18 Acceptance Test Cases.

- [ ] **7.1. Tạo test suite `CreatorClaimOAuthIntegrationTest.java`**:
  - [ ] Test TC-01: Ingest KOC mới -> `master_id = null`.
  - [ ] Test TC-04: Tạo thư mời -> sinh token hash SHA-256.
  - [ ] Test TC-05: OAuth sai kênh -> ném `IdentityMismatchException`.
  - [ ] Test TC-06: OAuth đúng kênh -> tạo Master M1, `A001.master_id = M1`, `source_type = AUTHORIZED`.
  - [ ] Test TC-07: Concurrent claim sessions -> Pessimistic row lock chặn race condition.
- [ ] **7.2. Tạo test suite `CreatorAccountLinkIntegrationTest.java`**:
  - [ ] Test TC-08: Kênh thứ 2 đã có trong DB -> tái sử dụng và gán `master_id = M1`.
  - [ ] Test TC-09: Kênh thứ 2 chưa có trong DB -> tạo mới gán `master_id = M1`.
  - [ ] Test TC-11: Kênh thứ 2 đã thuộc Master khác -> ghi bản ghi `creator_identity_conflict`.
- [ ] **7.3. Tạo test suite `DiscoveryHybridGroupingIntegrationTest.java`**:
  - [ ] Test TC-12: KOC có 2 kênh cùng Master -> Discovery gom thành 1 Card duy nhất, chọn kênh điểm cao nhất.
  - [ ] Test TC-13: 2 KOC chưa claim (`master_id = null`) -> Discovery hiển thị 2 Card riêng biệt.
- [ ] **7.4. Thực thi kiểm thử toàn diện**:
  - Chạy lệnh:
    ```bash
    mvn test -Dtest=CreatorClaimOAuthIntegrationTest,CreatorAccountLinkIntegrationTest,DiscoveryHybridGroupingIntegrationTest
    ```
  - **DoD**: Toàn bộ 18 test cases ch
  ạy xanh (Passed 100%), hoàn tất điều kiện nghiệm thu cho Hội đồng Review 2.
