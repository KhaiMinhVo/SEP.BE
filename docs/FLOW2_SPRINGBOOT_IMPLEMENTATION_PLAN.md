# Flow 2 Checklist dành riêng cho Spring Boot Repository (Backend Core)

Vì Crawler và AI sẽ được tách riêng ra một repository Python, nên checklist dưới đây chỉ tập trung vào những việc **bắt buộc phải làm trong mã nguồn Spring Boot này**. Spring Boot sẽ đóng vai trò là "Trái tim" (Lưu trữ DB, Cấu hình rule, Cung cấp API) chứ không trực tiếp đi cào dữ liệu.

## 1. Chuẩn bị Entity & Database (JPA / Flyway)
Backend cần chuẩn bị sẵn "cấu trúc chứa data" để đón dữ liệu từ Python gửi về:
- [ ] **Tạo `CreatorRecentPost` Entity**: Lưu 3 bài post gần nhất của Creator (platformPostId, postUrl, caption, views, likes, postedAt).
- [ ] **Cập nhật `PublicCreatorMetric` (M2)**: Thêm các cột cho AI Semantic (niche, category, creatorType, activityStatus).
- [ ] **Tạo Enums**: `CreatorType` (KOL, KOC, EXPERT...), `CreatorActivityStatus` (ACTIVE, DORMANT, INACTIVE...).
- [ ] **Flyway Migration**: Viết file `.sql` để tạo bảng và alter bảng cho các thay đổi trên.

## 2. Xây dựng API Contract (Để giao tiếp với Python)
Spring Boot phải mở các cổng API (Endpoints) hoặc cấu hình Hàng đợi (Queue) để Python gọi về:
- [ ] **Tạo DTO `CrawlerResultDto`**: DTO này map đúng 100% với file JSON mà Crawler trả về (bao gồm profile creator + danh sách 3 recent posts + source + collectedAt).
- [ ] **Viết `CreatorIngestionController`**: Endpoint `POST /internal/creators/ingest` để Python đẩy cục data JSON vừa cào xong vào Spring Boot.
- [ ] (Tùy chọn) **Cấu hình RabbitMQ/Redis**: Nếu chọn giao tiếp qua Queue thay vì HTTP, tạo Listener để hứng message từ Python.

## 3. Xây dựng Business Logic (Services)
Xử lý dữ liệu thô nhận được từ Python:
- [ ] **`CreatorNormalizationService`**: Chuyển đổi dữ liệu thô từ DTO thành Entity. Tính toán trung bình (`avgViews`, `avgLikes`, `engagementRate`) trước khi lưu.
- [ ] **`CreatorIdentityService`**: Check `platform + externalId` xem Creator này đã có trong DB chưa (để Insert mới hoặc Update bản ghi cũ).
- [ ] **`CreatorLifecycleService`**: Dựa vào `postedAt` của các bài post gần nhất để gán `activityStatus` (VD: >30 ngày không post thì set là INACTIVE).
- [ ] **`CreatorAcquisitionService`**: Đọc từ bảng `SeedQuery`, lấy ra danh sách các keyword/username cần cào, và bắn request (hoặc gửi Queue) sang cho Python Service.

## 4. Xây dựng Creator Discovery (M3 + M2)
Phục vụ API cho Frontend (Web/App) để Brand tìm kiếm Creator:
- [ ] **`CreatorDiscoveryController`**: API `POST /api/creators/search` nhận các filter từ Brand.
- [ ] **`CreatorDiscoveryService`**: 
  - Đọc `CampaignContext` (M3) để lấy tiêu chí (Niche, Follower, Platform...).
  - Dùng JPA Specification hoặc QueryDSL query vào bảng `PublicCreatorMetric` (M2).
  - Code logic **Similar Fallback**: Nếu query exact match ra ít hơn 30 người, tự tự động nới lỏng (relax) điều kiện query (bỏ bớt filter location, nới follower range) để trả về đủ danh sách.

## 5. Đưa Cấu Hình Ra Ngoài (Configurable Rules)
- [ ] Khai báo các tham số linh hoạt vào `application.yml` thay vì hard-code trong Java:
  - `discovery.fallback.threshold: 30` (Dưới 30 thì bật chế độ tìm kiếm tương đương).
  - `creator.lifecycle.dormant-days: 15`
  - `creator.lifecycle.inactive-days: 30`
