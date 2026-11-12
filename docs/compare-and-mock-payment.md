# Compare Shortlist và mock thanh toán

Các API shortlist/relationship (tạo, danh sách, chi tiết, sửa, xóa) kiểm tra quyền
sở hữu campaign từ tài khoản đang đăng nhập. ID item/relationship trong URL phải
thuộc đúng campaign và tài khoản đó; truy cập chéo Brand hoặc sai campaign trả 404.
Relationship tạo từ shortlist phải dùng item của cùng campaign và cùng Creator;
item khác Creator trả 400. Dữ liệu cũ gắn sai user không cấp quyền truy cập vào
campaign của Brand khác và không xuất hiện trong danh sách của owner.

Base URL `/api/v1`. Các request bên dưới cần `Authorization: Bearer <Brand access token>`.

## So sánh Creator

`POST /campaigns/{campaignId}/shortlist/compare`

```json
{"creatorIds":["UUID-Creator-1","UUID-Creator-2"]}
```

Chọn 2–5 Creator khác nhau trong shortlist của campaign thuộc Brand. Hỗ trợ KOC/KOL;
backend trả số liệu cho UI hiển thị song song, không chấm điểm hoặc xếp hạng.

Response `data` gồm `campaignId`, `creators` theo đúng thứ tự request. Mỗi phần tử gồm
`creatorId`, `platform`, `userName`, `displayName`, `profileUrl`, `avatarUrl`, `location`,
`creatorType`, `priority`, `note`, `sourceType`, `verifiedByInfluencerMatch`, `authorizedAt`,
`metrics`.

`metrics` là snapshot mới nhất theo collectedAt rồi UUID giảm dần; gồm metricId, niche,
followers, avgViews/avgLikes/avgComments/avgShares, engagementRate, dataConfidence,
freshnessStatus và collectedAt. Chưa có snapshot thì `metrics: null`; trường thiếu
không được biến thành 0. Frontend nên hiển thị “Chưa có dữ liệu” cho các giá trị thiếu.

Response không còn trường `rateCard`; frontend cần bỏ phần binding/hiển thị bảng giá.
Thông tin xác minh ownership vẫn được trả như trước.

HTTP 400 cho ID trùng, số lượng sai hoặc Creator ngoài shortlist; 404 khi campaign
không tồn tại/thuộc Brand khác; 401 khi chưa đăng nhập, 403 khi thiếu quyền
`MANAGE_OWN_SHORTLIST`. Request chỉ đọc dữ liệu, không gọi AI hoặc đổi shortlist.

## Mock thanh toán cho frontend

Chỉ dùng ở profile `dev` và bật `PAYMENT_MOCK_ENABLED=true` trước khi khởi động.
Mặc định tắt. Controller/service không được đăng ký nếu profile `prod` đang bật,
kể cả khi đồng thời có profile dev. Không thêm mock endpoint vào public allowlist.

1. Brand gọi `POST /billing/subscribe` với `{"planId":"UUID-plan"}`.
   Response `data` vẫn có `paymentUrl`, bổ sung `transactionCode`.
2. Thay cho mở VNPay sandbox, gọi `POST /billing/mock/webhook`:

   ```json
   {"transactionCode":"mã-từ-checkout","outcome":"SUCCESS"}
   ```

   Có thể dùng `FAILED` để mô phỏng thất bại. Không nhận số tiền hoặc subscriptionId
   từ frontend; backend lấy từ payment đã tạo.
3. Response `data` gồm transactionCode, paymentStatus, subscriptionId,
   subscriptionStatus, startDate, expirationDate. SUCCESS trả PAID/ACTIVE;
   FAILED trả FAILED/CANCELLED. Brand có thể xem lại gói qua API subscription hiện có.

Chỉ Brand sở hữu payment có permission `MANAGE_OWN_SUBSCRIPTION` được gọi. Payment
không tồn tại hoặc thuộc Brand khác trả 404. Outcome trái với trạng thái cuối hoặc
đã có subscription ACTIVE khác trả 409. Lặp cùng outcome trả 200 mà không gia hạn,
reset quota hoặc tạo audit trùng. Mock thực hiện thay đổi payment/subscription thật
trong database dev, nhưng không chuyển tiền hoặc gọi VNPay.

Mock và callback VNPay dùng chung khóa Brand → Payment và logic chuyển trạng thái.
Callback thật vẫn kiểm tra chữ ký, merchant, số tiền. Audit `MOCK_PAYMENT_WEBHOOK`
ghi dữ liệu trước/sau trong cùng transaction khi mock thay đổi trạng thái.

## Kiểm thử

```powershell
mvn '-Dtest=ComparePaymentPostgresIntegrationTest,MockPaymentConfigurationTest,RbacPostgresIntegrationTest' test
```

JDK 21 và Docker Linux containers. Test dùng PostgreSQL/pgvector riêng qua
Testcontainers, không tác động database dùng chung. Không có migration mới cho task này.
