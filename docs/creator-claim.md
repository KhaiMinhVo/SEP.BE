# Creator Claim Profile (Controlled Hybrid)

Để test không cần Google Cloud: bật `CREATOR_MOCK_LOGIN_ENABLED=true` ở profile dev,
gọi `POST /auth/dev/creator-login` không body để nhận token Creator test mới.
Identity DEV_MOCK được tách khỏi GOOGLE, chỉ được dùng để test claim khi mock bật ở dev. Cờ mặc định false; profile prod
luôn chặn mock. Không tự cấp ownership hoặc giả xác minh email Google.

Base URL: `http://localhost:8080/api/v1`. Các API dữ liệu dùng header
`Authorization: Bearer <accessToken>` và response bọc trong `data`.

## Luồng thử thủ công

1. Nhận email mời đăng ký Creator từ Admin/Data Manager, đặt mật khẩu và xác minh OTP, sau đó gọi `/auth/login`. Xem [hướng dẫn đăng ký](registration-and-email-otp.md). Creator phải ACTIVE và email đã xác minh; không cần Google identity.
2. Gọi `POST /creators/{creatorId}/claim`:

   ```json
   {"evidenceUrls":["https://example.com/ownership-proof"],"note":"Bằng chứng quản lý kênh"}
   ```

   Kết quả HTTP 201, `data.status=PENDING`. OTP xác minh email đăng nhập,
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
5. Admin/Data Manager có thể gọi `POST /data/creator-claims/{claimId}/revoke`
   với `{"reason":"Không còn quyền quản lý kênh"}`. Kênh trở về PUBLIC, owner và
   authorizedAt bị xóa; lịch sử claim được giữ. Creator có thể gửi claim mới.

Gửi claim, quyết định duyệt, từ chối claim cạnh tranh, thu hồi đều có audit.
Ingestion chỉ cập nhật dữ liệu công khai, không thay ownership.

## Kiểm thử tự động

Yêu cầu JDK 21, Maven; integration test cần Docker chạy Linux containers:

```powershell
mvn '-Dtest=CreatorClaimServiceTest,CreatorIngestionOwnershipTest,RolePermissionsTest,AuthControllerTest,JwtAuthVersionTest,CreatorServiceKeyFilterTest' test
mvn '-Dtest=CreatorClaimPostgresIntegrationTest,RbacPostgresIntegrationTest,RetiredRateCardPostgresIntegrationTest' test
```

Integration suite dùng PostgreSQL 16 + pgvector qua Testcontainers, chạy Flyway và
Hibernate validate, kiểm tra claim/duyệt/thu hồi, cạnh tranh đồng thời, phân quyền,
validation và ingestion giữ trạng thái AUTHORIZED. Không dùng database production.

## Thay đổi API: gỡ Rate Card

GET/PUT `/creators/{id}/rate-card` đã bị gỡ, trả 404 với tài khoản đã xác thực.
Hồ sơ Creator và Compare không còn trường `rateCard`; frontend cần bỏ binding/hiển thị tương ứng.
Migration V12 chỉ dọn override `MANAGE_OWN_RATE_CARD` và tăng version của role bị ảnh hưởng.
Từ V15, bảng `creator_rate_card` và giá cũ đã được xóa; audit lịch sử vẫn giữ. Xem [dọn schema V15](schema-cleanup-v15.md).
Claim, tick xác minh, Agreed Fee trong Collaboration và phí Relationship vẫn giữ nguyên.

Brand channel-claim invitations, duplicate/ownership blocking and SMTP behavior: [MVP invitation flow](claim-invitations-mvp.md). Brand claim links do not register Creator accounts.
