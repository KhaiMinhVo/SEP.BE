# InfluencerMatch Backend

Spring Boot 3.3, Java 21, PostgreSQL 16, Flyway, JWT authentication and RFC 9457 errors.

## Cách chạy chuẩn cho cả team (khuyến nghị)

Yêu cầu duy nhất: Docker Desktop đang chạy.

### 1. Chuẩn bị cấu hình

PowerShell:

```powershell
Copy-Item .env.example .env
```

macOS/Linux:

```bash
cp .env.example .env
```

`.env` chỉ dùng trên máy local và không được commit. Có thể bật tài khoản ADMIN phát triển trong file này:

```properties
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_EMAIL=poc-admin@example.local
BOOTSTRAP_ADMIN_PASSWORD=replace-with-a-strong-dev-password
BOOTSTRAP_ADMIN_FULL_NAME=PoC Administrator
```

Bootstrap ADMIN chỉ hoạt động với profile `dev`, mặc định tắt và không được dùng trong production.

### 2. Khởi động toàn bộ hệ thống

```powershell
docker compose up --build -d
docker compose ps
docker compose logs -f api
```

Compose sẽ tự khởi động PostgreSQL, chờ database healthy, chạy API và áp dụng Flyway V1–V8. Không cần cài Maven hoặc Java trên máy.

Các địa chỉ sau phải truy cập được:

- Health: http://localhost:8080/api/v1/actuator/health
- Swagger UI: http://localhost:8080/api/v1/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/api/v1/api-docs
- Auth demo: `docs/AuthPoc.http`
- Full Flow 1 HTTP demo: `docs/Flow1.http`

Postman:

1. Import `docs/postman/InfluencerMatch-Flow1.postman_collection.json`.
2. Import and select `docs/postman/InfluencerMatch-Local.postman_environment.json`.
3. Set the ADMIN credentials to the same values configured in `.env`.
4. Run requests in numeric order. Use a fresh database or a new `brandEmail` when rerunning the create flow.

Kiểm tra health bằng PowerShell:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/actuator/health
```

### 3. Dừng hệ thống

Giữ lại dữ liệu PostgreSQL:

```powershell
docker compose down
```

Xóa cả dữ liệu local và tạo database sạch ở lần chạy sau:

```powershell
docker compose down -v
```

Lệnh `down -v` làm mất toàn bộ dữ liệu PostgreSQL local.

## Chế độ phát triển backend nhanh

Chế độ này phù hợp khi cần sửa code và restart Spring Boot nhanh. Yêu cầu Java 21 và Maven 3.9+ trên máy.

```powershell
docker compose up -d postgres
mvn spring-boot:run
```

Nếu máy không có Maven, chạy backend trong Maven container:

```powershell
docker compose up -d postgres
docker run --rm -it `
  --name influencermatch-api-dev `
  --network sepbe_default `
  -p 8080:8080 `
  --env-file .env `
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://postgres:5432/influencermatch_dev" `
  -v "${PWD}:/workspace" `
  -v sep-be-m2:/root/.m2 `
  -w /workspace `
  maven:3.9.11-eclipse-temurin-21 `
  mvn spring-boot:run
```

Không chạy đồng thời service Compose `api` và container dev trên cùng port 8080. Nếu báo trùng tên container:

```powershell
docker rm -f influencermatch-api-dev
```

## Chạy kiểm thử

Máy có Maven:

```powershell
mvn test
```

Không có Maven:

```powershell
docker run --rm `
  -v "${PWD}:/workspace" `
  -v sep-be-m2:/root/.m2 `
  -w /workspace `
  maven:3.9.11-eclipse-temurin-21 `
  mvn test
```

Lưu ý: Testcontainers cần Docker daemon đang chạy. Nếu không có Docker, các test PostgreSQL sẽ thất bại, không tự bỏ qua. Nên chạy Maven trực tiếp trên máy có Docker Desktop.

## Các lỗi local thường gặp

### Sai mật khẩu PostgreSQL

Nếu `.env` đã đổi sau khi volume database được tạo, PostgreSQL vẫn giữ credential cũ. Với dữ liệu local không cần giữ:

```powershell
docker compose down -v
docker compose up --build -d
```

### Port 8080 hoặc tên container đang được sử dụng

```powershell
docker ps -a
docker compose down
docker rm -f influencermatch-api influencermatch-api-dev
```

Chỉ xóa các container local nêu trên; không xóa volume nếu cần giữ dữ liệu.

### Xem trạng thái và log

```powershell
docker compose ps
docker compose logs --tail 200 postgres
docker compose logs --tail 200 api
```

## Auth endpoints

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`
- `GET /api/v1/auth/google`
- `POST /api/v1/auth/google/exchange`

## Google OAuth2 login

1. Create an OAuth 2.0 Web Client in Google Cloud Console.
2. Add `http://localhost:8080/api/v1/login/oauth2/code/google` as an authorized redirect URI.
3. Configure `GOOGLE_OAUTH_ENABLED=true`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, and `GOOGLE_FRONTEND_CALLBACK_URL` in `.env`.
4. Open `http://localhost:8080/api/v1/auth/google` in a browser.
5. Exchange the one-time callback code through `POST /api/v1/auth/google/exchange` using `{ "code": "..." }`.

Google login is available only to `BRAND`. A first login creates an active Brand user but does not create a Brand Profile. Access and refresh tokens behave the same as password login.

On `develop`, migration `V3__google_oauth2_login.sql` upgrades the V1/V2 snake_case schema.
The older `khai` V1-V10 migration history is not interchangeable with this schema.
Use a separate development database when switching between those histories; do not delete shared data or repair Flyway checksums to bypass the mismatch.
The frontend callback must exchange the code once within 60 seconds and handle the returned login response.

Public registration luôn tạo role `BRAND`; `/admin/**` yêu cầu role `ADMIN`.

## RBAC và phạm vi triển khai

Ba role cố định: `BRAND` (25 permission), `DATA_MANAGER` (12), `ADMIN` (22).
Tổng cộng 43 permission, không wildcard; danh sách tính từ role trong database và trả qua
`user.permissions` khi login/refresh/Google exchange, `permissions` khi gọi `/auth/me`.
Bốn quyền chung cấp cho cả ba role; tám quyền vận hành dữ liệu dùng chung cho Manager và Admin.
Nguồn ma trận: `security/Permission.java` và `security/RolePermissions.java`.

Các API hiện có đã được bảo vệ:

- Auth profile; Admin User (xem, đổi status, cấp role).
- Brand Profile, Campaign và Creator Discovery: Brand chỉ thao tác dữ liệu của mình.
- Admin đọc Brand/Campaign để hỗ trợ bằng `VIEW_BRAND_SUPPORT_DATA`, không tạo/sửa/archive thay Brand.
- Plan: Brand đọc gói đang hoạt động; Admin quản lý bằng `MANAGE_PLAN`.
- `/subscriptions`: Brand quản lý subscription của mình; không tự kích hoạt gói trả phí.
- `/admin/subscriptions`: Admin quản lý subscription với audit, không cho Brand/Data Manager gọi.
- `/billing/subscribe`: Brand tạo checkout VNPay từ tài khoản trong JWT; callback xác minh chữ ký,
  merchant, số tiền và giao dịch trước khi thay đổi dữ liệu. Callback lặp được xử lý idempotent.
- `/admin/audit-logs`: Admin đọc audit có phân trang và lọc actor/action/entity/thời gian.
- `/creators/ingest`: chỉ service key, không chấp nhận JWT của bất kỳ role nào thay key.

Đường dẫn chuẩn là `/api/v1/plans`, `/api/v1/subscriptions` (context-path đã chứa `/api/v1`).
Alias đường dẫn billing cũ có prefix lặp vẫn được giữ để tương thích.

### Cấp role và thu hồi token

`PATCH /api/v1/admin/users/{id}/role` nhận `{ "role": "DATA_MANAGER", "reason": "..." }`,
yêu cầu `ASSIGN_USER_ROLE`. `PATCH .../{id}/status` nhận `status` và `reason` tùy chọn.
Brand đã có profile không thể chuyển sang role nội bộ; phải dùng tài khoản riêng.
Không được khóa, disable hoặc hạ role Admin hoạt động cuối cùng, kể cả request đồng thời.

Migration V4 thêm `auth_version`, constraint role và một bản ghi khóa chung `rbac_guard`.
Mỗi thay đổi role/status thực sự tăng version, revoke refresh token và ghi audit trong cùng transaction.
JWT kiểm tra role/status/version hiện tại từ database. JWT cũ thiếu claim coi là version 0;
sau khi version tăng, mở khóa không làm token cũ hợp lệ lại. Thao tác không đổi giá trị không tăng version.
OAuth session chỉ lưu handshake Google, không dùng làm đăng nhập cho API JWT.

### Ingestion service key

Đặt `CREATOR_INGESTION_API_KEY` trong environment backend và header `X-Service-Key` của crawler.
Không cấu hình trả `503 INGESTION_NOT_CONFIGURED`; thiếu/sai key trả `401`.
Key chỉ dùng cho ingestion, không cấp quyền API quản trị hay dữ liệu riêng.
Docker Compose hiện truyền biến trong `.env` vào container API qua `env_file`.

### Chưa triển khai trong đợt RBAC này

Permission Notification, Recommendation, Shortlist/CRM, Collaboration/Outcome/Learning,
Usage/Billing read và các tác vụ Data Manager mới chỉ được khai báo nếu chưa có API tương ứng.
Không tạo workflow claim, dynamic role/permission hoặc quota engine mới. Không có entitlement/quota
gate đầy đủ cho mọi API hiện tại; RBAC không thay thế các kiểm tra này khi chúng được triển khai.
Không thể xem các permission đã khai báo là chức năng đã hoạt động.
