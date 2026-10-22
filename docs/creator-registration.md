# Brand and Creator Registration

Base URL `/api/v1`. Mọi response thành công dùng `ApiResponse`; lỗi dùng ProblemDetail hiện tại.

## Brand: đăng ký công khai

`POST /auth/register`:
```json
{"email":"brand@example.com","password":"Password123!","fullName":"Brand"}
```
Luôn tạo BRAND chưa xác minh. Không nhận `role`, kể cả `role: null` hoặc `role: BRAND`;
request có trường này trả 400. Sau 201, nhập OTP qua `/auth/verify-email`, rồi `/auth/login`.
Email trùng trả 409; không ghi đè mật khẩu/role. Mọi email hợp lệ đều được chấp nhận.

## Creator: đăng ký bằng lời mời

1. Admin/Data Manager có `INVITE_CREATOR` gửi:
   ```http
   POST /data/creator-invitations
   Authorization: Bearer <reviewer-token>
   Content-Type: application/json
   ```
   ```json
   {"email":"creator@example.com"}
   ```
   Trả 201, `data` có `id`, `email`, `expiresAt`; không có token. Không tạo User tại bước mời.
   Chỉ email nhận link `<frontend>/auth/creator/register?token=...`.
2. Trang frontend đọc token, gọi `POST /auth/creator-invitations/resolve`:
   ```json
   {"token":"<token-from-email>"}
   ```
   Trả 200 với ID/email/thời hạn; chưa tiêu thụ token. Hiển thị email chỉ đọc.
3. `POST /auth/creator/register`:
   ```json
   {"invitationToken":"<token-from-email>","password":"Password123!","fullName":"Creator"}
   ```
   Trả 201, tạo CREATOR chưa xác minh, tiêu thụ lời mời và gửi OTP.
   Không nhận thêm `email`, `role` hoặc trường tùy ý; email được lấy từ lời mời.
4. Xác minh OTP qua `/auth/verify-email`, rồi đăng nhập qua `/auth/login` như Brand.
   `/auth/resend-verification` dùng chung cho cả hai loại tài khoản.

Link mời có hiệu lực 48 giờ, dùng một lần, token ngẫu nhiên 256 bit và chỉ lưu SHA-256.
Mời lại cùng email thay token cũ; tối thiểu 60 giây và tối đa 5 email mời/giờ trượt/email.
Email đã có tài khoản trả 409, không tự chuyển Brand thành Creator. Mời Creator không
cấp ownership. Sau đăng nhập, Creator vẫn gửi Claim và chờ người duyệt kiểm tra bằng chứng.

## Legacy Google account: đặt mật khẩu lần đầu

Google Login, `/auth/google`, `/auth/google/creator`, `/auth/google/exchange`,
`/oauth2/authorization/google` và `/login/oauth2/code/google` đã bị gỡ.
Tài khoản đã xác thực gọi các route cũ nhận 404; request chưa xác thực có thể bị 401
bởi security filter. Bảng identity/exchange và dữ liệu cũ vẫn được giữ.

1. `POST /auth/password-setup/request`:
   ```json
   {"email":"legacy-google@example.com"}
   ```
   Trả 200 chung, không tiết lộ tài khoản đủ điều kiện. Chỉ gửi mã cho tài khoản ACTIVE,
   BRAND/CREATOR, email đã xác minh, có Google identity lịch sử và chưa có mật khẩu.
2. `POST /auth/password-setup/confirm`:
   ```json
   {"email":"legacy-google@example.com","otp":"000042","newPassword":"NewPassword123!"}
   ```
   Trả 200, đặt BCrypt, tăng authVersion và thu hồi refresh token cũ trong cùng transaction.
   Không tự đăng nhập; sau đó dùng `/auth/login`. Xác nhận lại không được chấp nhận.
   Không đổi role/status, ID tài khoản, ownership, collaboration hoặc subscription.

Mã đặt mật khẩu có HMAC theo mục đích riêng, không dùng lẫn mã xác minh đăng ký.
Thời hạn 10 phút, sai tối đa 5 lần; 4 lần đầu 400, lần thứ 5 và sau đó 429.
Gửi lại sau 60 giây; ngân sách 5 OTP/giờ/tài khoản dùng chung với OTP đăng ký.
Mã sai/hết hạn/không đủ điều kiện trả 400. Đây không phải API quên/đổi mật khẩu.

## Error and transaction rules

- 400: body không hợp lệ, token mời sai/hết hạn/đã dùng (`CREATOR_INVITATION_INVALID`),
  OTP sai/hết hạn (`EMAIL_OTP_INVALID_OR_EXPIRED`).
- 401/403: chưa đăng nhập/không có quyền mời; email chưa xác minh không thể đăng nhập.
- 409 `EMAIL_ALREADY_EXISTS`: tài khoản đã tồn tại khi mời hoặc đăng ký.
- 429 `EMAIL_OTP_RATE_LIMITED`: vượt giới hạn gửi hoặc thử mã.
- 503 `EMAIL_DELIVERY_UNAVAILABLE`: SMTP/OTP secret chưa cấu hình hoặc gửi thất bại.
- SMTP lỗi rollback tài khoản/mã/lời mời và lịch sử gửi tương ứng. Audit lời mời nằm
  cùng transaction, không chứa token/link. Lỗi audit rollback lời mời.
- Các thao tác tạo tài khoản/tiêu thụ lời mời/đặt mật khẩu dùng cùng account-write guard;
  chỉ một request cạnh tranh thành công. Bộ đếm nhập sai vẫn commit khi API trả lỗi.
- SMTP là tác vụ ngoài database: thư có thể đã gửi trước lỗi commit/timeout; người dùng
  có thể cần yêu cầu lại. Không ghi OTP, mật khẩu hoặc invitation token vào log/audit/response.

## Cấu hình và frontend

Thêm `CREATOR_INVITATION_FRONTEND_BASE_URL=http://localhost:5173` để chọn nơi link mời dẫn đến.
Không nhận redirect URL từ request. Cấu hình SMTP và `EMAIL_OTP_SECRET` theo
[Email Verification](email-verification.md). Google OAuth env đã bị bỏ khỏi `.env.example`
và không còn được backend sử dụng; `.env` thật không bị thay đổi trong đợt này.

Frontend cần bỏ nút Google, giữ form Brand không truyền role, thêm màn hình gửi lời mời
cho người có `INVITE_CREATOR`, trang `/auth/creator/register` đọc token, form OTP chung,
và form đặt mật khẩu lần đầu cho tài khoản Google cũ. Backend chưa tạo các trang này.
Không dùng Google SDK/Firebase và không gọi exchange code.

## Migration and tests

V14 chỉ thêm bảng lời mời, lịch sử gửi lời mời và OTP đặt mật khẩu; giữ nguyên bảng cũ.
Không tự chạy V14 trên database dùng chung trong đợt triển khai mã nguồn này.

```powershell
.\mvnw.cmd '-Dtest=CreatorInvitationServiceTest,CreatorRegistrationPostgresIntegrationTest,CreatorClaimServiceTest,CreatorClaimPostgresIntegrationTest' test
.\mvnw.cmd test
```

Test dùng SMTP/clock giả lập và PostgreSQL/pgvector Testcontainers riêng. Không gửi thư thật.
Mock Creator chỉ hoạt động ở dev khi bật flag; không cấp ownership hoặc cho phép
đăng nhập thay tài khoản có sẵn. Kết quả test xem [Backend Test Cases](backend-test-cases.md).
