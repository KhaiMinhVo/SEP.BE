# Role Permission Management

Base URL `/api/v1`. Hai API yêu cầu Bearer token của ADMIN. Chỉ cấu hình các
quyền sẵn có của BRAND, CREATOR và DATA_MANAGER; ADMIN luôn giữ quyền mặc định.
Không tạo role mới, không cấp quyền chéo role hoặc override cho từng tài khoản.

## Danh mục

`GET /admin/role-permissions` trả `ApiResponse<List<RoleView>>`. Mỗi RoleView gồm
`role`, `version` và `permissions`. Mỗi permission gồm:

- `permission`: mã enum dùng khi cập nhật.
- `name`, `module`: tên chức năng và nhóm chức năng cho giao diện.
- `defaultEnabled`: role có quyền theo ma trận mặc định hay không.
- `enabled`: quyền hiệu lực sau cấu hình.
- `implemented`: đã có endpoint nghiệp vụ thực thi permission.
- `editable`: Admin có được bật/tắt quyền đó cho role này không.

Danh mục hiển thị cả quyền ngoài role và chức năng chưa triển khai nhưng chúng
không editable. Permission chưa triển khai có thể `defaultEnabled=true`; điều đó
không có nghĩa chức năng đã tồn tại. ADMIN có `version=0` và chỉ đọc.

## Cập nhật từng phần

`PATCH /admin/role-permissions/BRAND`

```json
{
  "version": 0,
  "changes": [
    {"permission": "MANAGE_OWN_SHORTLIST", "enabled": false},
    {"permission": "RECORD_OWN_OUTCOME", "enabled": false}
  ]
}
```

Lấy `version` từ GET trước khi cập nhật. Response `data` là RoleView sau cập nhật;
version tăng một lần cho mỗi lần thay đổi thực sự. Những quyền không gửi giữ nguyên.
Muốn khôi phục quyền, gửi `enabled=true` với version hiện hành. Không có cấu hình
override nghĩa là dùng mặc định; không cần seed bản ghi cho từng permission.
Gửi cùng trạng thái hiện tại không tăng version và không tạo audit mới.

- `400`: role/permission không hợp lệ; ADMIN; quyền ngoài role; chức năng chưa
  triển khai; version thiếu/âm; changes rỗng/quá 100; permission trùng; phần tử,
  permission hoặc enabled null. Body dùng boolean JSON cho enabled.
- `401`: chưa xác thực hoặc token không hợp lệ.
- `403`: người gọi không phải Admin.
- `409 OPTIMISTIC_CONFLICT`: version cũ; tải lại GET, xem cấu hình mới rồi cập nhật.

Khóa theo role tuần tự hóa cập nhật; hai Admin dùng cùng version chỉ một người
thành công. Audit `UPDATE_ROLE_PERMISSIONS` lưu actor, role, ma trận trước/sau và
thời điểm trong cùng transaction; audit lỗi thì cấu hình/version rollback.

## Ma trận chức năng đã triển khai và có thể cấu hình

BRAND:

- Authentication: `VIEW_OWN_PROFILE`.
- Creator Profile: `VIEW_CREATOR_PROFILE`.
- Brand Profile: `CREATE_BRAND_PROFILE`, `VIEW_OWN_BRAND_DATA`, `UPDATE_BRAND_PROFILE`.
- Campaign: `CREATE_CAMPAIGN`, `VIEW_OWN_CAMPAIGN`, `UPDATE_CAMPAIGN`, `CHANGE_CAMPAIGN_STATUS`.
- Creator Discovery: `SEARCH_CREATOR`.
- Shortlist/Compare: `MANAGE_OWN_SHORTLIST`.
- Relationship: `MANAGE_OWN_RELATIONSHIP`.
- Collaboration: `MANAGE_OWN_COLLABORATION`.
- Outcome: `RECORD_OWN_OUTCOME`.
- Creator Review: `RECORD_OWN_CREATOR_REVIEW`.
- Billing: `VIEW_PLAN`, `MANAGE_OWN_SUBSCRIPTION` (gồm checkout và webhook mock dev).

CREATOR:

- `VIEW_OWN_PROFILE`, `VIEW_CREATOR_PROFILE`.
- `CLAIM_CREATOR_PROFILE`, `VIEW_OWN_CREATOR_CLAIM`.

DATA_MANAGER:

- `VIEW_OWN_PROFILE`, `VIEW_CREATOR_PROFILE`.
- `REVIEW_CREATOR_CLAIM` (xem bằng chứng, duyệt, từ chối, thu hồi).
- `REVIEW_CREATOR_CLASSIFICATION` (hàng đợi chất lượng dữ liệu và duyệt/sửa Niche).

Một permission có thể bảo vệ nhiều API: tắt MANAGE_OWN_SHORTLIST chặn cả đọc,
thêm, xóa shortlist và Compare. Các kiểm tra ownership và điều kiện nghiệp vụ
vẫn giữ nguyên. Các API shortlist/relationship còn yêu cầu xem campaign sở hữu,
nên tắt VIEW_OWN_CAMPAIGN cũng chặn các thao tác này. Bật một quyền không tự bật
quyền khác; frontend nên diễn giải dependency này khi hiển thị công tắc.

## Hiệu lực và tương thích

JWT không chứa ma trận quyền làm nguồn cấp quyền. Mỗi request hợp lệ nạp quyền
hiệu lực từ database một lần cho role, dùng cùng snapshot cho Spring authorities,
service checks và response permissions. Không cache giữa các request/instance.
Thay đổi đã commit có hiệu lực từ request tiếp theo; request đang xử lý tiếp tục
theo snapshot ban đầu. Token hiện tại vẫn hợp lệ, không cần đăng nhập lại.

Login, refresh, Google exchange, mock Creator login và `/auth/me` trả permissions
hiệu lực. Frontend cần tải lại permissions để cập nhật menu; backend luôn chặn API
bị tắt bằng 403. Tắt VIEW_OWN_PROFILE cũng chặn `/auth/me`; frontend có thể nhận
lại permissions khi refresh/login. Logout và refresh không phụ thuộc công tắc này.

Không hủy dữ liệu/tác vụ đang chạy, không ảnh hưởng service-key ingestion hoặc
callback VNPay công khai đã xác minh. Tắt checkout không ngăn đối soát giao dịch
đã tạo trước đó qua callback VNPay. Không thay role/status/auth_version hoặc quota.

V11 tạo hai bảng cấu hình, mặc định không tắt quyền nào. Chỉ chạy migration theo
quy trình triển khai; quá trình phát triển/test không chạy vào database dùng chung.

## Kiểm thử

JDK 21, Maven và Docker Linux containers:

```powershell
mvn '-Dtest=EffectivePermissionServiceTest,RolePermissionServiceTest,RolePermissionPostgresIntegrationTest' test
mvn test
```

PostgreSQL/pgvector Testcontainers dùng database riêng. Test kiểm tra migration,
defaults, request cache, cùng token trước/sau thay đổi, các response xác thực,
quyền ADMIN cố định, validation, optimistic conflict, no-op và audit rollback.

## Permission đã gỡ

`MANAGE_OWN_RATE_CARD` không còn trong enum, quyền mặc định hoặc danh mục Admin.
Migration V12 xóa override cũ và tăng version cấu hình chỉ cho role có override bị xóa;
Admin dùng version cũ sẽ gặp conflict. Không xóa bảng giá hoặc audit lịch sử.
