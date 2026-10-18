# Hàng đợi chất lượng dữ liệu KOC

Hai API dùng Bearer access token của ADMIN hoặc DATA_MANAGER và permission
`REVIEW_CREATOR_CLASSIFICATION`. Base URL: `/api/v1`.

## Lấy hàng đợi

`GET /admin/data-quality/pending?page=0&size=20`

Response chuẩn `ApiResponse` chứa `data.items`, `page`, `size`, `totalElements`,
`totalPages`. Page âm được đưa về 0; size giới hạn 1–100. Item gồm:

```json
{
  "metricId": "UUID snapshot",
  "creatorId": "UUID Creator",
  "platform": "TIKTOK",
  "userName": "example",
  "displayName": "Example KOC",
  "profileUrl": "https://example.com/profile",
  "niche": "Beauty",
  "contentSummary": "Nội dung công khai dùng để kiểm tra phân loại",
  "dataConfidence": 0.65,
  "collectedAt": "2026-10-17T10:00:00"
}
```

Chỉ lấy snapshot mới nhất của từng Creator theo `collectedAt DESC, id DESC`, rồi
lọc `creatorType=KOC`, `dataConfidence < 0.7`, chưa được duyệt. Confidence null
không vào hàng đợi. KOC dùng giá trị phân loại chính xác `KOC`.
Sắp xếp confidence, collectedAt và metricId tăng dần. Snapshot lịch sử không
xuất hiện dù có confidence thấp hơn snapshot mới nhất.

## Duyệt hoặc sửa Niche

`PUT /admin/data-quality/{metricId}/approve`

```json
{"niche":"Beauty"}
```

Gửi `{}` hoặc `{"niche":null}` để giữ Niche. Niche mới được trim, không được rỗng,
chỉ chứa khoảng trắng hoặc dài hơn 120 ký tự. Không hỗ trợ xóa Niche qua API này.
HTTP 200 trả item sau khi duyệt, có thêm `qualityReviewedBy` (UUID người duyệt)
và `qualityReviewedAt`. Confidence AI được giữ nguyên; snapshot ra khỏi pending.
Generated search_vector cập nhật theo Niche mới trong PostgreSQL.

- 400: body, UUID hoặc Niche không hợp lệ.
- 401: chưa xác thực; 403: thiếu quyền.
- 404: snapshot không tồn tại.
- 409, code `DATA_QUALITY_REVIEW_CONFLICT`: đã duyệt, không đủ điều kiện hoặc
  đã có snapshot mới hơn. Tải lại hàng đợi trước khi thao tác tiếp.

Approval và audit `APPROVE_DATA_QUALITY` nằm trong cùng transaction. Audit lưu
snapshot ID, dữ liệu trước/sau, Niche, reviewer và thời điểm duyệt. Hai người duyệt
đồng thời chỉ một người thành công. Ingestion tiếp tục tạo snapshot chưa duyệt;
snapshot mới có confidence thấp sẽ quay lại hàng đợi. Không thay quyền sở hữu kênh
hoặc Rate Card. Data Manager không được truy cập các API Admin khác nhờ hai API này.

## Migration và kiểm thử

V10 thêm reviewer/timestamp nullable vào `public_creator_metric`, constraint cặp
trường và index cho truy vấn latest/pending. Snapshot cũ mặc định chưa duyệt.
Triển khai qua quy trình Flyway thông thường; không chạy migration tự động vào
database dùng chung trong quá trình phát triển task.

```powershell
mvn '-Dtest=DataQualityPostgresIntegrationTest,RolePermissionsTest,CreatorClaimServiceTest,CreatorIngestionOwnershipTest,RbacPostgresIntegrationTest' test
```

Cần JDK 21 và Docker Linux containers. Integration test dùng PostgreSQL 16/pgvector
riêng qua Testcontainers, kiểm tra Flyway/Hibernate, ngưỡng confidence, latest snapshot,
validation, phân quyền, audit/rollback, generated search vector và cạnh tranh duyệt/ingestion.
