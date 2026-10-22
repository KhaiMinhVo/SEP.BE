# 📖 HƯỚNG DẪN TEST TOÀN BỘ CÁC FLOW TRÊN SWAGGER UI
> **Hệ thống**: InfluencerMatch Backend (SEP_BE)  
> **Địa chỉ Swagger UI**: [http://localhost:8080/api/v1/swagger-ui/index.html](http://localhost:8080/api/v1/swagger-ui/index.html)  
> **OpenAPI JSON Docs**: [http://localhost:8080/api/v1/api-docs](http://localhost:8080/api/v1/api-docs)

---

## 🔑 1. CHUẨN BỊ TÀI KHOẢN & XÁC THỰC (AUTHENTICATION)

Để test các API trên Swagger, bạn cần xác thực bằng nút **`Authorize 🔓`** ở góc trên bên phải màn hình Swagger UI:

### 1.1. Lấy Token Đăng Nhập
Gửi request tới API Auth:
- **`POST /api/v1/auth/login`**:
  - **Tài khoản Brand**: `{ "email": "brand@example.com", "password": "Password@123" }`
  - **Tài khoản Data Manager / Admin**: `{ "email": "admin@example.com", "password": "Password@123" }`
  - **Tài khoản KOC (Creator)**: `{ "email": "koc@example.com", "password": "Password@123" }` (hoặc sau khi claim)
- Copy chuỗi `accessToken` từ response.

### 1.2. Cấu hình vào Swagger
1. Bấm nút **`Authorize 🔓`** ở đầu trang Swagger.
2. Tại mục **`bearerAuth`**: Nhập `Bearer <YOUR_ACCESS_TOKEN>` (hoặc chỉ paste token nếu swagger tự thêm Bearer).
3. Bấm **Authorize** -> **Close**.
*(Đối với API Ingestion của Crawler: Sử dụng header `X-Service-Key` cấu hình trong `.env` / `CREATOR_INGESTION_API_KEY`, mặc định dev: `dev-creator-service-key`)*.

---

## 🌐 2. FLOW 1: CRAWLER INGEST KOC (PHASE 4)
> **Mục tiêu**: Mô phỏng crawler thu thập tài khoản TikTok/YouTube từ mạng xã hội. Tài khoản mới luôn có `master_id = NULL` và `source_type = PUBLIC`.

### Endpoint: `POST /api/v1/creators/ingest`
- **Header**: `X-Service-Key: dev-creator-service-key`
- **Request Body**:
```json
{
  "platform": "TIKTOK",
  "externalId": "tt_linh_beauty_01",
  "userName": "linh.beauty",
  "displayName": "Linh Beauty Reviews",
  "profileUrl": "https://www.tiktok.com/@linh.beauty",
  "avatarUrl": "https://avatar.url/linh.jpg",
  "followers": 85000,
  "avgViews": 15000,
  "avgLikes": 4200,
  "engagementRate": 4.94,
  "creatorType": "KOC",
  "niche": "BEAUTY",
  "category": "Skincare & Cosmetics",
  "location": "Ho Chi Minh City",
  "isStableExternalId": true,
  "crawlRunId": "crawl_run_batch_001",
  "observedAt": "2026-10-22T08:00:00"
}
```
- **Kỳ vọng Response (200 OK)**:
```json
{
  "data": {
    "creatorId": "4a7b9c1d-...",
    "metricId": "8f1e2d3c-...",
    "status": "SUCCESS",
    "message": "Creator data ingested successfully"
  }
}
```
👉 *Lưu lại `creatorId` (ví dụ: `A001`) để dùng cho các bước tiếp theo.*

---

## 🔍 3. FLOW 2: DISCOVERY & HYBRID GROUPING (PHASE 4)
> **Mục tiêu**: Brand tìm kiếm KOC trên hệ thống. Kiểm tra KOC chưa claim hiển thị nhãn `PUBLIC_PROFILE`.

### Endpoint: `GET /api/v1/campaigns/{campaignId}/discovery`
- **Authorize**: Đăng nhập với tài khoản **BRAND**.
- **Params**:
  - `campaignId`: UUID chiến dịch của Brand.
  - `keyword`: `Beauty`
  - `page`: `0`, `size`: `10`
- **Kỳ vọng Response (200 OK)**:
  - Trường `claimStatus`: `"PUBLIC_PROFILE"`.
  - Trường `masterId`: `null`.
  - Thẻ Creator Card hiển thị thông tin kênh độc lập.

---

## 📩 4. FLOW 3 & 4: BRAND GỬI LỜI MỜI & KOC OAUTH CLAIM KÊNH ĐẦU TIÊN (PHASE 5)
> **Mục tiêu**: Brand mời KOC nhận kênh; KOC xác thực Proof of Control tạo `CreatorMaster` M1 nguyên tử.

### Bước 4.1: Brand tạo lời mời Claim
- **Endpoint**: `POST /api/v1/creators/invitations`
- **Authorize**: Tài khoản **BRAND**.
- **Request Body**:
```json
{
  "creatorId": "4a7b9c1d-...", 
  "email": "koc.linh@gmail.com"
}
```
- **Kỳ vọng Response (200 OK)**:
```json
{
  "data": {
    "id": "invitation_uuid_123",
    "creatorId": "4a7b9c1d-...",
    "email": "koc.linh@gmail.com",
    "status": "PENDING",
    "expiresAt": "2026-10-29T..."
  }
}
```
- **Kiểm tra Log Server**:
  Console backend sẽ in ra claim URL chứa token ngẫu nhiên:
  `Sending creator claim invitation to email='koc.linh@gmail.com' (claimUrl='/claim-profile?token=bb6fe1d069b8ccc31cb22d2d836e2cf71c831aeed5e165e37239027264415b6f')`
  👉 *Copy chuỗi `token` này.*

---

### Bước 4.2: KOC bấm vào link để Verify Token (Không cần login)
- **Endpoint**: `GET /api/v1/creators/claim/invitation/verify`
- **Params**:
  - `token`: `bb6fe1d069b8ccc31cb22d2d836e2cf71c831aeed5e165e37239027264415b6f`
- **Kỳ vọng Response (200 OK)**:
```json
{
  "data": {
    "creatorId": "4a7b9c1d-...",
    "platform": "TIKTOK",
    "userName": "linh.beauty",
    "displayName": "Linh Beauty Reviews",
    "avatarUrl": "https://avatar.url/linh.jpg"
  }
}
```

---

### Bước 4.3: Bắt đầu OAuth (Lấy URL ủy quyền)
- **Endpoint**: `POST /api/v1/creators/claim/oauth/{platform}/start`
- **Path Param**: `platform = TIKTOK` (hoặc `YOUTUBE`)
- **Query Param**: `token = <TOKEN_TỪ_BƯỚC_4.1>`
- **Response**: Trả về URL OAuth tương ứng của platform.

---

### Bước 4.4: KOC Submit OAuth Callback để Claim hồ sơ
Hệ thống hỗ trợ cơ chế **Mô phỏng Proof of Control (Simulation)** giúp test ngay trên Swagger mà không cần gọi API thật của TikTok/Google:
- **Endpoint**: `POST /api/v1/creators/claim/oauth/{platform}/callback`
- **Path Param**: `platform = TIKTOK`
- **Authorize**: Đăng nhập với tài khoản KOC (nếu chưa có thì hệ thống tự gán theo email lời mời).

#### 🟢 Kịch bản 1: Claim thành công (Đúng kênh)
- **Request Body**:
```json
{
  "token": "bb6fe1d069b8ccc31cb22d2d836e2cf71c831aeed5e165e37239027264415b6f",
  "code": "sim_auth_code_ok"
}
```
- **Kỳ vọng Response (200 OK)**:
```json
{
  "data": {
    "masterId": "e1f2a3b4-...",
    "creatorId": "4a7b9c1d-...",
    "canonicalName": "Linh Beauty Reviews",
    "status": "AUTHORIZED"
  }
}
```
*(Hệ thống đã tạo Master M1, gán kênh sang `AUTHORIZED`, lưu token bảo mật và tự động đồng bộ shortlist/relationship).*

#### 🔴 Kịch bản 2: Đăng nhập sai kênh (Hacker / Tài khoản khác)
- **Request Body**:
```json
{
  "token": "bb6fe1d069b8ccc31cb22d2d836e2cf71c831aeed5e165e37239027264415b6f",
  "code": "sim-mismatch-wrong-channel"
}
```
- **Kỳ vọng Response (409 Conflict)**:
```json
{
  "code": "IDENTITY_MISMATCH",
  "message": "Proof of control failed: Authenticated account does not match target creator"
}
```

---

## 🔗 5. FLOW 5: KOC PORTAL KẾT NỐI KÊNH THỨ 2, 3 QUA OAUTH (PHASE 6)
> **Mục tiêu**: KOC đã có Master M1 vào trang Portal cá nhân để kết nối thêm kênh YouTube, Instagram.

### Bước 5.1: Xem danh sách các kênh hiện có của Master
- **Endpoint**: `GET /api/v1/creators/portal/accounts`
- **Authorize**: Tài khoản **CREATOR** (KOC đã sở hữu Master M1).
- **Kỳ vọng Response**: Hiển thị danh sách kênh kèm trạng thái `AUTHORIZED`.

---

### Bước 5.2: Bắt đầu kết nối kênh thứ 2
- **Endpoint**: `POST /api/v1/creators/portal/accounts/connect/{platform}/start`
- **Path Param**: `platform = YOUTUBE`
- **Response**: Trả về URL OAuth kết nối của YouTube.

---

### Bước 5.3: Hoàn tất kết nối kênh thứ 2 (Callback)
- **Endpoint**: `POST /api/v1/creators/portal/accounts/connect/{platform}/callback`
- **Path Param**: `platform = YOUTUBE` (hoặc `INSTAGRAM`, `TIKTOK`)

#### 🔹 Case 1: Kênh thứ 2 đã có sẵn trong DB qua crawler (`master_id = NULL`)
- *Chuẩn bị*: Ingest 1 kênh YouTube qua `POST /creators/ingest` với `externalId = UC_LINH_YOUTUBE`, `userName = linh_yt`.
- **Request Body**:
```json
{
  "authorizationCode": "sim:UC_LINH_YOUTUBE:linh_yt",
  "redirectUri": "http://localhost:5173/portal/accounts/callback"
}
```
- **Kỳ vọng Response (200 OK)**:
```json
{
  "data": {
    "status": "LINKED",
    "accountId": "uuid-cua-kenh-youtube",
    "masterId": "uuid-master-M1",
    "platform": "YOUTUBE",
    "externalId": "UC_LINH_YOUTUBE",
    "message": "Channel successfully linked to your master profile"
  }
}
```

#### 🔹 Case 2: Kênh thứ 2 hoàn toàn mới (chưa từng cào)
- **Request Body**:
```json
{
  "authorizationCode": "sim:ig_linh_lifestyle:linh.lifestyle",
  "redirectUri": "http://localhost:5173/portal/accounts/callback"
}
```
- **Kỳ vọng Response (200 OK)**:
```json
{
  "data": {
    "status": "CREATED_AND_LINKED",
    "masterId": "uuid-master-M1",
    "platform": "INSTAGRAM",
    "externalId": "ig_linh_lifestyle",
    "message": "New channel registered and successfully linked to your master profile"
  }
}
```

#### 🔹 Case 3: Re-auth kênh đã thuộc chính mình (Gia hạn Token)
- Gửi lại request kết nối cho chính kênh đã link ở Case 1 hoặc 2.
- **Kỳ vọng Response (200 OK)**:
```json
{
  "data": {
    "status": "ALREADY_LINKED",
    "message": "Channel is already linked to your master profile; authentication tokens refreshed"
  }
}
```

#### 🔹 Case 4: Tranh chấp kênh (Kênh đã thuộc Master M2 khác)
- *Tình huống*: Kênh `tt_belongs_to_other` đã thuộc về Master M2 khác. KOC M1 cố gắng kết nối kênh này.
- **Request Body**:
```json
{
  "authorizationCode": "sim:tt_belongs_to_other:other_user",
  "redirectUri": "http://localhost:5173/portal/accounts/callback"
}
```
- **Kỳ vọng Response (200 OK)**:
```json
{
  "data": {
    "status": "CONFLICT_PENDING_REVIEW",
    "message": "This channel is currently linked to another creator profile. A transfer request has been submitted to Data Manager for review."
  }
}
```
👉 *Hệ thống giữ nguyên quyền sở hữu của M2, đồng thời sinh 1 bản ghi vào bảng tranh chấp `creator_identity_conflict`.*

---

## ⚖️ 6. FLOW 6: DATA MANAGER / ADMIN XỬ LÝ TRANH CHẤP KÊNH (PHASE 6)
> **Mục tiêu**: Data Manager xem danh sách tranh chấp kênh và đưa ra quyết định chuyển nhượng hoặc từ chối.

### Bước 6.1: Xem danh sách tranh chấp đang chờ xử lý
- **Endpoint**: `GET /api/v1/admin/creator-conflicts`
- **Authorize**: Đăng nhập với tài khoản **ADMIN** hoặc **DATA_MANAGER**.
- **Query Params**:
  - `status`: `PENDING_MANAGER`
  - `page`: `0`, `size`: `10`
- **Kỳ vọng Response (200 OK)**:
```json
{
  "data": {
    "items": [
      {
        "id": "conflict_uuid_999",
        "conflictType": "ACCOUNT_ALREADY_LINKED",
        "sourceAccountId": "account_uuid_...",
        "sourceAccountPlatform": "TIKTOK",
        "sourceAccountExternalId": "tt_belongs_to_other",
        "targetMasterId": "uuid-master-M1",
        "status": "PENDING_MANAGER",
        "createdAt": "2026-10-22T..."
      }
    ],
    "total": 1,
    "page": 0,
    "size": 10
  }
}
```

---

### Bước 6.2: Phê duyệt chuyển quyền sở hữu kênh (APPROVE_TRANSFER)
- **Endpoint**: `POST /api/v1/admin/creator-conflicts/{id}/resolve`
- **Path Param**: `id = conflict_uuid_999`
- **Authorize**: Tài khoản **ADMIN** / **DATA_MANAGER**.
- **Request Body**:
```json
{
  "decision": "APPROVE_TRANSFER",
  "resolutionNotes": "Đã đối soát giấy tờ tùy thân và hợp đồng quản lý kênh hợp lệ"
}
```
- **Kỳ vọng Response (200 OK)**:
```json
{
  "data": {
    "id": "conflict_uuid_999",
    "status": "RESOLVED",
    "resolvedByUserId": "admin_user_uuid",
    "resolutionNotes": "Đã đối soát giấy tờ tùy thân và hợp đồng quản lý kênh hợp lệ"
  }
}
```
👉 *Sau khi duyệt, kênh mạng xã hội được chuyển sang Master M1, tự động cập nhật dual reference shortlist và ghi nhật ký kiểm toán danh tính.*

---

### Bước 6.3: (Lựa chọn khác) Từ chối chuyển kênh (REJECT)
- **Request Body**:
```json
{
  "decision": "REJECT",
  "resolutionNotes": "Bằng chứng không hợp lệ, không chứng minh được quyền sở hữu"
}
```
- **Kỳ vọng Response (200 OK)**:
  - `status: "REJECTED"`, kênh giữ nguyên cho chủ cũ.

---

## 🎯 BẢNG TỔNG KẾT MÃ TEST MÔ PHỎNG (SIMULATION CODES CHEAT-SHEET)

| Trường hợp Test | Giá trị `authorizationCode` / `code` | Kết quả hành vi |
| :--- | :--- | :--- |
| **Claim đúng kênh** | `sim_auth_code_ok` hoặc bất kỳ | Khớp target creator, claim thành công -> tạo Master M1 |
| **Claim sai kênh (TC-05)** | `sim-mismatch-channel` | Báo lỗi `409 IDENTITY_MISMATCH` |
| **Link kênh chỉ định (Custom ID)** | `sim:<external_id>:<username>` | Trả về đúng `externalId` và `userName` chỉ định để test Case 1, 3, 4 |
| **Link kênh mới ngẫu nhiên (Case 2)** | `new_code_999` | Tự sinh `provider_id_new_code_999` chưa có trong DB |

---

## ❓ CÂU HỎI THƯỜNG GẶP & GIẢI THÍCH KIẾN TRÚC (FAQ)

### 📌 Tại sao các tài khoản không phải KOL/KOC (cơ quan nhà nước, shop bán hàng, tài khoản cá nhân) vẫn được lưu trong Database?

#### 1. Nguồn gốc dữ liệu thu thập (Crawler Data Pool)
* Toàn bộ dữ liệu ban đầu trong bảng `creator` và `public_creator_metric` được nạp tự động từ **Crawler Service** dựa trên hashtag, video thịnh hành (trending) hoặc từ khóa công khai từ TikTok.
* Bot cào tự động ở tầng công khai với mục tiêu bao phủ thị trường (Data Lake), nên bất kỳ tài khoản public nào có video liên quan đều được lưu tạm vào cơ sở dữ liệu với trạng thái ban đầu:
  $$\text{source_type} = \text{'PUBLIC'}, \quad \text{master_id} = \text{NULL}, \quad \text{owner_id} = \text{NULL}$$

#### 2. Kiến trúc phân tầng dữ liệu (Identity Tiering)
Hệ thống phân tách rạch ròi 2 cấp độ tài khoản:

| Tiêu chí phân tầng | Public Profile (`PUBLIC_PROFILE`) | Verified Partner (`LINKED_PROFILE` / `AUTHORIZED`) |
| :--- | :--- | :--- |
| **Nguồn dữ liệu** | Thu thập tự động qua Bot Crawler từ mạng xã hội. | Người thật đăng ký tài khoản trên nền tảng. |
| **Xác thực danh tính** | Chưa xác minh (`source_type = 'PUBLIC'`). | Đã vượt qua kiểm tra **OAuth Proof-of-Control** (`source_type = 'AUTHORIZED'`). |
| **Master Identity** | `master_id = NULL` (Kênh đơn lẻ, độc lập). | `master_id != NULL` (Gắn với Master Creator M1, gom nhóm đa kênh). |
| **Hợp tác & Ký kết** | Chỉ xem số liệu công khai, gửi lời mời (Invite). | Tham gia chiến dịch, nhận booking, tạo Rate Card, thanh toán. |

#### 3. Bộ lọc phân loại thông minh (AI Classification Filter)
* Dữ liệu cào về được AI tự động gán nhãn `creator_type`:
  * `KOL`: Người có tầm ảnh hưởng lớn, nghệ sĩ, chuyên gia (>100k followers).
  * `KOC`: Người sáng tạo nội dung đánh giá sản phẩm, unboxing, affiliate.
  * `BRAND`: Tài khoản doanh nghiệp, cơ quan truyền thông, shop bán hàng.
  * `USER`: Tài khoản người dùng cá nhân thông thường.
* **Ý nghĩa thực tế**: Brand khi dùng tính năng **Discovery** có thể sử dụng bộ lọc `creator_type` để chỉ tìm kiếm `KOL`/`KOC` và loại bỏ hoàn toàn các tài khoản `BRAND`/`USER` khỏi danh sách đề xuất chiến dịch.

