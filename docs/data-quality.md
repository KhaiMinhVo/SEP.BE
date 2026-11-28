# Hàng đợi chất lượng dữ liệu KOC

## Chuẩn hóa dữ liệu PostgreSQL đang có

Data Manager dùng `POST /api/v1/data/crawler/db-normalization/preview` với ví dụ
`{"limit":100,"platform":"TIKTOK"}` để nhận kế hoạch trước/sau, `runId`, đích DB,
danh sách cần kiểm tra nội dung và các trường có thể sửa từ dữ liệu đã lưu.
`GET /api/v1/data/crawler/db-normalization/reports/{runId}` đọc lại kế hoạch;
`POST /api/v1/data/crawler/db-normalization/apply` nhận `{"planId":"normalize-..."}`.
Backend xác thực role DATA_MANAGER, chuyển danh tính người thao tác và giữ service key ở server.
`POST /api/v1/data/crawler/db-normalization/reclassify` với `{"limit":5}` kiểm tra lại
ngành từ caption gốc và trả `taxonomyProposal` cùng trích dẫn thực tế. Category/niche cũ
không được đưa vào làm bằng chứng. Phải có hai bài khác nội dung, quote khớp nguyên văn
và confidence tối thiểu 0.85. Chỉ trả đề xuất cần duyệt; proxy luôn ép `dry_run=true`.
`GET /api/v1/data/crawler/db-normalization/content-report` xem báo cáo nội dung mới nhất.

Công cụ thực thi Python nằm trong repo crawler, không nằm trong backend:
`scripts/normalize_existing_db.py preview --all`, sau đó `apply --plan-id <runId>`.
Cấu hình `NORMALIZATION_DATABASE_URL`, hoặc đủ DB_HOST/DB_PORT/DB_NAME/DB_USER và
DB_PASSWORD hoặc DB_PASS ở crawler. `DB_NORMALIZATION_API_KEY` cần giống nhau ở hai
dịch vụ; nếu không đặt thì dùng khóa service ingestion hiện có. Chi tiết ở README crawler.

Đây là chuẩn hóa hợp đồng dữ liệu, không phải phép đo AI phân loại đúng bao nhiêu phần trăm.
Nó sửa alias và category mâu thuẫn với niche, tính ER từ số tương tác/follower đã lưu,
trích summary từ bài gốc và hạ FRESH đã quá cũ thành STALE. Nhãn đã được duyệt được giữ.
Không ghi ngày quan sát/ngày thu thập mới, không cào lại, không thay bài đăng, ownership,
visibility hay giá. Vector cũ mất phù hợp sẽ được xóa để dùng tìm kiếm lexical trước khi cào lại.

Kế hoạch hết hạn sau một giờ. Mỗi creator được khóa và kiểm tra lại snapshot/bằng chứng trước
khi ghi; thay đổi cùng bản audit trước/sau nằm trong một transaction. Snapshot đổi do cào lại
hoặc duyệt mới sẽ báo conflict. Kết quả có `appliedCount`, `alreadyAppliedCount`, `conflictCount`
và `applyResults`; không coi batch ghi một phần là thành công toàn bộ. Không kết nối được
PostgreSQL sẽ báo lỗi, không âm thầm chạy trên SQLite.

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

`engagementRate` trong backend dùng tỷ lệ thập phân (`5.2% = 0.052`), còn kết quả
crawler được tính theo phần trăm. Mapper chuyển đơn vị trước khi ingest; migration
V56 chuẩn hóa các snapshot crawler cũ dựa trên `crawl_run_id`.

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
snapshot mới có confidence thấp sẽ quay lại hàng đợi. Không thay quyền sở hữu kênh. Data Manager không được truy cập các API Admin khác nhờ hai API này.

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
