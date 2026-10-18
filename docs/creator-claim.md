# Creator Claim Profile (Controlled Hybrid)

Để test không cần Google Cloud: bật `CREATOR_MOCK_LOGIN_ENABLED=true` ở profile dev,
gọi `POST /auth/dev/creator-login` không body để nhận token Creator test mới.
Collection CreatorClaimTasks mặc định `creatorLoginMode=mock`, chạy request 00 rồi
02–11; chọn `google` để dùng OAuth thật. Identity DEV_MOCK được tách khỏi GOOGLE,
chỉ được claim/duyệt/sửa giá khi mock bật ở dev. Cờ mặc định false; profile prod
luôn chặn mock. Không tự cấp ownership hoặc giả xác minh email Google.

Base URL: `http://localhost:8080/api/v1`. Các API dữ liệu dùng header
`Authorization: Bearer <accessToken>` và response bọc trong `data`.

## Luồng thử thủ công

1. Mở `GET /auth/google/creator` bằng trình duyệt. Sau callback, frontend nhận
   one-time `code`; gọi `POST /auth/google/exchange` với `{"code":"..."}` để lấy token.
   Cần bật Google OAuth và cấu hình client/callback hợp lệ. Creator phải ACTIVE;
   login Creator không chuyển một tài khoản BRAND/ADMIN/DATA_MANAGER thành Creator.
2. Gọi `POST /creators/{creatorId}/claim`:

   ```json
   {"evidenceUrls":["https://example.com/ownership-proof"],"note":"Bằng chứng quản lý kênh"}
   ```

   Kết quả HTTP 201, `data.status=PENDING`. Google xác minh danh tính đăng nhập,
   quyền sở hữu kênh vẫn cần người duyệt kiểm tra bằng chứng. URL không được backend
   tự tải; bằng chứng chỉ chủ claim và người duyệt được đọc qua các API tương ứng.
3. Creator theo dõi `GET /creator-claims/me`. Admin/Data Manager dùng token riêng
   gọi `GET /data/creator-claims?status=PENDING`, rồi
   `PATCH /data/creator-claims/{claimId}/review`:

   ```json
   {"decision":"APPROVED","reason":"Đã kiểm tra quyền quản lý kênh"}
   ```

   Có thể chọn `REJECTED`; reason bắt buộc. Khi approve, các claim PENDING khác
   của kênh bị từ chối trong cùng transaction. Chỉ một claim được APPROVED mỗi kênh.
4. `GET /creators/{creatorId}` trả `sourceType=AUTHORIZED`,
   `verifiedByInfluencerMatch=true` và `authorizedAt`. Tick thể hiện InfluencerMatch
   xác minh ownership, không phải tick của TikTok/YouTube hoặc quyền đọc analytics riêng tư.
5. Owner gọi `PUT /creators/{creatorId}/rate-card`:

   ```json
   {"items":[{"contentType":"VIDEO","price":1500000,"currency":"VND","note":"Giá mong muốn"}]}
   ```

   PUT thay toàn bộ bảng giá; `{"items":[]}` xóa giá hiển thị. Mỗi loại VIDEO,
   POST, STORY, LIVESTREAM chỉ xuất hiện một lần. Giá không âm, tối đa hai chữ số
   thập phân; tiền tệ theo ISO 4217. `GET /creators/{creatorId}/rate-card` trả nguồn
   `CREATOR_DECLARED`: đây là giá Creator đề xuất, không phải phí đã thỏa thuận.
6. Admin/Data Manager có thể gọi `POST /data/creator-claims/{claimId}/revoke`
   với `{"reason":"Không còn quyền quản lý kênh"}`. Kênh trở về PUBLIC, owner và
   authorizedAt bị xóa, giá bị ẩn nhưng lịch sử được giữ. Creator có thể gửi claim mới.

Gửi claim, quyết định duyệt, từ chối claim cạnh tranh, thu hồi và sửa giá đều có audit.
Ingestion chỉ cập nhật dữ liệu công khai, không thay ownership hoặc Rate Card.

## Kiểm thử tự động

Yêu cầu JDK 21, Maven; integration test cần Docker chạy Linux containers:

```powershell
mvn '-Dtest=CreatorClaimServiceTest,CreatorIngestionOwnershipTest,GoogleAuthServiceTest,RolePermissionsTest,AuthControllerTest,JwtAuthVersionTest,CreatorServiceKeyFilterTest' test
mvn '-Dtest=CreatorClaimPostgresIntegrationTest,GoogleOAuthPostgresIntegrationTest,RbacPostgresIntegrationTest' test
```

Integration suite dùng PostgreSQL 16 + pgvector qua Testcontainers, chạy Flyway và
Hibernate validate, kiểm tra claim/duyệt/thu hồi, cạnh tranh đồng thời, quyền sửa giá,
validation và ingestion giữ trạng thái AUTHORIZED. Không dùng database production.
