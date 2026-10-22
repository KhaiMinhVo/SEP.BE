# Registration Email Verification

## Luồng API

Base URL: `/api/v1`. Các endpoint dưới đây không yêu cầu Bearer token.

1. `POST /auth/register`:
   ```json
   {"email":"brand@example.com","password":"Password123!","fullName":"Brand"}
   ```
   Trả 201 với `ApiResponse`, không có token hoặc OTP. Tạo BRAND chưa xác minh,
   gửi email chứa mã 6 chữ số. Tài khoản đang chờ xác minh vẫn chiếm email;
   đăng ký lại trả 409 và không ghi đè mật khẩu. Dùng resend nếu cần mã mới.
2. `POST /auth/verify-email`:
   ```json
   {"email":"brand@example.com","otp":"000042"}
   ```
   OTP là **chuỗi** để giữ số 0 đầu. Trả 200, không tự đăng nhập.
3. `POST /auth/login` bằng email/mật khẩu như trước để nhận access/refresh token.
4. Khi cần mã khác, `POST /auth/resend-verification`:
   ```json
   {"email":"brand@example.com"}
   ```
   Trả 200 với cùng cấu trúc phản hồi cho email không tồn tại/đã xác minh và không gửi
   email ở hai trường hợp này. `meta.timestamp` và `meta.traceId` riêng cho từng request.

Mã có hiệu lực 10 phút, sử dụng một lần. Sau 60 giây được gửi lại; tối đa 5 email
trong một giờ trượt cho mỗi tài khoản, tính cả email đăng ký. Gửi lại thành công
thay mã cũ và đặt lại lượt thử. Không gửi lại mã ngẫu nhiên trùng mã ngay trước đó.

## Error contract

| HTTP | Code | Điều kiện |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | Email/body hoặc định dạng OTP không hợp lệ; không tăng lượt thử |
| 400 | EMAIL_OTP_INVALID_OR_EXPIRED | Không có mã, mã sai hoặc hết hạn |
| 403 | EMAIL_NOT_VERIFIED | Đúng mật khẩu nhưng chưa xác minh email |
| 409 | EMAIL_ALREADY_EXISTS | Đăng ký email đã tồn tại |
| 409 | EMAIL_ALREADY_VERIFIED | Gửi verify cho tài khoản đã xác minh |
| 429 | EMAIL_OTP_RATE_LIMITED | Lần sai thứ 5, mã đã hết lượt thử hoặc vượt giới hạn gửi |
| 503 | EMAIL_DELIVERY_UNAVAILABLE | SMTP/OTP secret chưa cấu hình hoặc gửi email thất bại |

Nhập sai tối đa 5 lần cho mỗi mã; 4 lần đầu trả 400, lần thứ 5 trả 429 và khóa mã.
Mã đúng cũng không được chấp nhận sau khi đã hết lượt thử; yêu cầu resend theo giới hạn.
Sai/hết hạn không thay cờ xác minh. Lỗi SMTP rollback việc tạo tài khoản hoặc thay mã,
giữ mã cũ nếu resend thất bại. SMTP không nằm trong transaction database: email có thể
đã được chuyển tiếp trước khi lỗi commit/timeout được phát hiện; người dùng có thể cần thử lại.

## Cấu hình SMTP

Thêm dependency Spring Mail; khai báo các biến sau trên môi trường chạy (mẫu trong `.env.example`):

```dotenv
EMAIL_VERIFICATION_ENABLED=true
EMAIL_OTP_SECRET=<random-secret-at-least-32-characters>
SMTP_HOST=<smtp-server>
SMTP_PORT=587
SMTP_USERNAME=<smtp-user>
SMTP_PASSWORD=<smtp-password-or-app-password>
SMTP_FROM=<verified-sender-email>
SMTP_AUTH=true
SMTP_STARTTLS_ENABLED=true
```

Sử dụng thông tin SMTP do nhà cung cấp email cấp. Nếu dịch vụ yêu cầu app password,
điền app password thay cho mật khẩu đăng nhập thông thường. Không commit secret thật.
Timeout kết nối/đọc/ghi là 5 giây. TLS được yêu cầu khi `SMTP_STARTTLS_ENABLED=true`.
Có thể dùng SMTP nội bộ để thử ở dev bằng cấu hình host/port/auth/TLS tương ứng.

Mặc định gửi email bị tắt. Khi chưa cấu hình, register/resend trả 503; không có mã
cố định, endpoint đọc OTP hay cơ chế bỏ qua xác minh ở dev/production.

## Dữ liệu và tương thích

- V13 đặt `email_verified=true` cho tài khoản cũ; không yêu cầu xác minh lại.
  Tài khoản đăng ký email/mật khẩu mới đặt false; trạng thái ACTIVE không thay thế xác minh.
- OTP chỉ lưu HMAC-SHA256, gắn với user ID và secret riêng. Mật khẩu vẫn dùng BCrypt.
  Không có OTP trong response, audit hoặc application log.
- Xác minh và resend khóa User trước khi xử lý mã. Hai request verify chỉ một request
  thành công. Bộ đếm nhập sai được commit dù API trả lỗi.
- Login, JWT filter, permission check và refresh đều chặn tài khoản chưa xác minh.
  Admin đổi role/status không làm email tự được xác minh.
- Google Login đã gỡ. Tài khoản Google cũ chưa có mật khẩu sử dụng OTP đặt mật khẩu
  lần đầu; không yêu cầu đăng ký lại hoặc đổi role. Xem [Creator Registration](creator-registration.md).
- Creator đăng ký qua link mời và dùng chung API verify/resend/login; đăng ký công khai chỉ tạo Brand.
- Frontend cần màn hình nhập OTP và resend sau đăng ký, rồi chuyển về đăng nhập.
  Không có thay đổi frontend trong đợt backend này.
- Không bao gồm quên mật khẩu, đổi email hoặc OTP mỗi lần đăng nhập.

## Kiểm thử

```powershell
.\mvnw.cmd '-Dtest=EmailVerificationServiceTest,SmtpVerificationEmailSenderTest,EmailVerificationPostgresIntegrationTest,AuthServiceTest,AuthControllerTest,AuthSessionPostgresIntegrationTest,BackendApplicationTests' test
.\mvnw.cmd test
```

Suite BackendApplicationTests là API smoke test trên H2 với dialect H2 rõ ràng.
Unit test dùng Mockito; PostgreSQL test dùng database Testcontainers riêng và sender/clock
được giả lập. Không gửi email thật hoặc dùng Google Cloud. Kết quả thực thi được cập nhật
trong [Backend Test Cases](backend-test-cases.md); không xác nhận SMTP thật khi chưa cấu hình.
