# InfluencerMatch — Package Diagram & Class Diagrams

Ngày đối chiếu: **26/10/2026**. Phạm vi: working tree của `SEP.BE`, bao gồm code Meta/Instagram OAuth đang có trên máy và chưa commit. Các hình dùng tên tiếng Anh như mã nguồn để đưa vào báo cáo SDD; phần giải thích dùng tiếng Việt.

## Mở và sử dụng

- [Package diagram](package-diagram/index.html): 1 hình.
- [Class diagrams](class-diagram/index.html): 6 hình.
- [State diagrams](state-diagram/index.html): 12 hình.

- [State diagrams theo tài liệu — bộ xem riêng](state-diagram/index.html) · [Căn cứ và giả định SRS/SDD](state-diagram/README.md).
- [Mở bộ xem sơ đồ](index.html): chọn hình, phóng to/thu nhỏ, mở SVG, tải PNG hoặc mã PlantUML. Hoạt động offline.
- [Package diagram — PNG](package-diagram/rendered/01-package-diagram.png) · [SVG](package-diagram/rendered/01-package-diagram.svg) · [PlantUML](package-diagram/01-package-diagram.puml).
- [Class diagram tổng quan — PNG](class-diagram/rendered/02-class-overview.png) · [SVG](class-diagram/rendered/02-class-overview.svg) · [PlantUML](class-diagram/02-class-overview.puml).

Đưa hình **01** vào phần Package Design và hình **02** vào phần Domain Class Overview. Các hình **03–07** là bản chi tiết cho Detailed Design; nên dùng riêng từng hình, trang ngang, thay vì ép toàn bộ hệ thống vào một trang A4. SVG giữ nét khi phóng lớn; PNG tiện chèn vào Word. Class diagram tổng quan rút gọn thuộc tính và quan hệ phụ; không phải ERD đầy đủ.

## Các bản chi tiết

1. [03 — Brand, Campaign & Discovery](class-diagram/rendered/03-class-campaign-discovery.svg): BrandContext (M4), CampaignContext (M3), dịch vụ quản lý campaign, Gemini autofill, hybrid discovery và client embedding. Có kế thừa BaseEntity.
2. [04 — Shortlist, CRM & Collaboration](class-diagram/rendered/04-class-shortlist-collaboration.svg): interface/implementation, quyền sở hữu campaign, shortlist, contact stage, collaboration, outcome, review và evidence asset. Đây là bản gần với phong cách class diagram trong ảnh mẫu nhất.
3. [05 — Creator Identity & Public Intelligence](class-diagram/rendered/05-class-creator-identity.svg): User, CreatorMaster, kênh Creator, public metrics (M2), posts, manual claim, lời mời claim và phiên Meta/Instagram OAuth.
4. [06 — Authentication & RBAC](class-diagram/rendered/06-class-auth-rbac.svg): AuthService/AuthServiceImpl, JWT, refresh token, email OTP, lời mời đăng ký Creator, role và permission overrides.
5. [07 — Subscription & Payment](class-diagram/rendered/07-class-billing.svg): Plan, Subscription, Payment, các service/repository và VNPay settlement.

Sơ đồ được chia vào `package-diagram/`, `class-diagram/` và `state-diagram/`. Mỗi thư mục chứa mã `.puml`, trang xem `index.html` và thư mục `rendered/` cho PNG/SVG. Script dùng chung `generate_diagrams.py` và `render.ps1` nằm ở thư mục gốc này. [source-index.json](class-diagram/source-index.json) liên kết tên lớp được sử dụng đến đường dẫn Java thực tế.

## Căn cứ và quy ước

- Các hình chỉ giữ tiêu đề và nội dung UML; ô ghi chú, khung chú giải và caption dưới hình đã được bỏ. Phần giải thích và các giới hạn của sơ đồ được trình bày riêng trong tài liệu này.
- Hai tài liệu người dùng cung cấp: Capstone Project Document của **FA26SE287 / GFA26SE108**, đặc biệt mục chức năng và **III.3.1.5 Entity Relationship Diagram**; và **Capstone Project Register**, mục Functional Requirements/Backend Processing & AI Recommendation.
- Ảnh 1 và 2 dùng làm mẫu ký hiệu/bố cục UML. Tên CampusFacilityManagement và lớp mượn thiết bị trong mẫu không thuộc dự án này.
- Package lấy từ `src/main/java/com/influencermatch/backend`. Tên lớp, thuộc tính và chữ ký phương thức lấy từ code. Quan hệ lấy từ annotation JPA; các quan hệ một-một cốt lõi đối chiếu thêm migration SQL.
- Nét liền có mũi tên: association có hướng truy cập. `1`, `0..1`, `0..*` đặt ở đầu tương ứng của quan hệ. Không suy diễn composition chỉ vì có foreign key.
- Nét đứt: dependency. Tam giác rỗng với nét đứt: implements; tam giác rỗng với nét liền: extends.
- Phương thức hiển thị kiểu tham số và kiểu trả về; bỏ tên tham số để giảm chiều ngang. Chỉ chọn phương thức nghiệp vụ tiêu biểu. Getter/setter, constructor và code Lombok được lược bỏ.
- Hình 02 hiển thị `id` kế thừa ngay trong lớp để đọc độc lập. Các hình chi tiết có thể lược bỏ trường BaseEntity và đường kế thừa; chỉ hình 03/06 vẽ BaseEntity rõ ràng. Không phải mọi entity trong repo đều kế thừa BaseEntity.
- Dependency giữa package và service được chọn lọc, không phải toàn bộ import graph. Nhiều file có wildcard import thừa nên không dùng import đơn thuần để khẳng định một dependency.

## Những điểm cần thống nhất giữa SRS/SDD và code

**1. Kiến trúc package thực tế.** Backend đang chia theo nghiệp vụ (`auth`, `campaign`, `creator`, `relationship`, …), rồi mới chia `controller/service/repository/model/dto/enums` bên trong. Không có các package tổng `Core`, `Service.interface`, `Service.implements`, `mappers` như hình mẫu. Một số service có interface (AuthService, ShortlistService, RelationshipService), các service khác là lớp trực tiếp. Flyway nằm trong `src/main/resources/db/migration`, không phải package Java. TaxonomyController hiện gọi EntityManager trực tiếp.

**2. Creator và CreatorMaster.** Trong bảng mô tả tài liệu, `creator` là danh tính tổng và `creatorPlatformAccount` là kênh. Code hiện tại dùng `CreatorMaster` cho danh tính đã claim và `Creator` cho từng kênh. `CreatorMaster.user` là bắt buộc và duy nhất; `Creator.master` có thể null. Một kênh public chưa claim vẫn tồn tại, có metrics/posts, và có thể được shortlist. `Creator.getOwner()` suy ra User qua master, không phải một cột/association owner riêng.

**3. Four-Memory chưa phải một tính năng hoàn chỉnh.**

- **M2**: `PublicCreatorMetric` lưu các lần thu thập, freshness và confidence; tương ứng gần với `publicMetricSnapshot` của tài liệu. Profile hiện tại nằm ở Creator, số liệu snapshot ở PublicCreatorMetric. `Post` lưu nội dung công khai.
- **M3**: `CampaignContext` và cơ chế version trong CampaignService có trong Java. Tuy nhiên CreatorDiscoveryService hiện tạo `DiscoveryContext` từ Campaign trực tiếp, không lưu một RecommendationRun tham chiếu snapshot M3.
- **M4**: `BrandContext` có `profileContext`, `learnedPatterns`, `evidenceRefs`, `contextVersion`. Sự tồn tại của trường learnedPatterns không chứng minh pipeline tự học đã triển khai.
- **M1/learning**: migration V1 tạo `historical_campaign` và `brand_pattern`; chưa tìm thấy entity/service Java tương ứng để tạo HistoricalEvidence hoặc thực hiện Post-Campaign Learning. Outcome/Review hiện có là dữ liệu đầu vào có thể dùng cho phần này.
- Chưa có các lớp `RecommendationConfig`, `RecommendationRun`, `RecommendationResult`, dịch vụ đánh giá đủ M1–M4, fee estimation và explanation như tài liệu mô tả. Không đưa các lớp giả định này vào sơ đồ **implemented**.
- CreatorDiscoveryService có hybrid lexical/semantic search và fallback; đó là discovery hiện có. AiServiceClient gọi endpoint embedding ngoài repo. Không có source Python/FastAPI trong repo này để vẽ lớp nội bộ của AI service.

**4. Taxonomy, contact, notification.** Tài liệu có Category/CreatorCategory/CreatorContact và thực thể Platform. Code dùng chuỗi platform ở Creator, các trường category/niche/contact ở PublicCreatorMetric và API đọc niche; chưa có các entity Java riêng theo mô hình đó. V1 có bảng `platform` và `notification`, nhưng có bảng không đồng nghĩa đã có lớp hay API nghiệp vụ. Enum `brand.enums.Platform` cũng không đại diện đầy đủ mọi giá trị platform đang dùng ở luồng OAuth.

**5. Billing và collaboration payment khác nhau.** `Payment` gắn Subscription/BrandProfile, phục vụ thanh toán gói qua VNPay. `CollaborationPaymentStatus` là trạng thái ghi nhận phí hợp tác, không phải association tới Payment. Rate Card đã bị loại bỏ (V12/V15), không đưa vào hình. Quota có các trường dữ liệu; không khẳng định mọi API đã có cơ chế chặn quota.

**6. Claim và đăng ký tài khoản.** `auth.model.CreatorRegistrationInvitation` phục vụ mời đăng ký tài khoản; `creator.model.CreatorInvitation` phục vụ mời claim kênh. Meta/Instagram OAuth xác minh quyền với kênh của Creator đã đăng nhập, không thay thế luồng đăng ký/đăng nhập hệ thống.

**7. EvidenceAsset.** Entity và repository có trong code, nhưng `CollaborationService.recordOutcome` hiện không tạo bản ghi EvidenceAsset. Hình thể hiện cấu trúc dữ liệu, không khẳng định workflow upload evidence đã hoàn thiện.

## Tái tạo và kiểm tra

Chạy từ thư mục gốc repository:

```powershell
python docs/diagrams/generate_diagrams.py
# Cần Java và PlantUML 1.2025.2; đường dẫn JAR tùy máy.
powershell -ExecutionPolicy Bypass -File docs/diagrams/render.ps1 -PlantUmlJar target/uml-tools/plantuml.jar -JavaExecutable 'C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot\bin\java.exe'
```

`generate_diagrams.py` chỉ dùng Python standard library, đọc source để kiểm tra các tên field/method đã chọn và xuất PlantUML. Chỉnh danh sách lớp/member trong script khi muốn thay đổi phạm vi; chỉnh `.puml` trực tiếp để chỉnh riêng layout (chạy lại generator sẽ ghi đè). `render.ps1` tìm các file `.puml` trong thư mục được chọn và thư mục con, xuất SVG/PNG vào `rendered/` cạnh từng file nguồn. Bỏ `-DiagramDirectory` để xuất toàn bộ 19 hình; dùng `-DiagramDirectory docs/diagrams/class-diagram` để chỉ xuất class diagram. Script chạy cục bộ, không gửi code hay tài liệu lên dịch vụ render online. JAR nằm trong thư mục build được gitignore, không thuộc bộ tài liệu.

Kiểm tra đã thực hiện: sinh các file PlantUML từ code, render bằng PlantUML, kiểm tra số lượng/kích thước SVG/PNG và xem trực quan bố cục. Không thay đổi code ứng dụng; không chạy lại test backend cho thay đổi chỉ thêm tài liệu này.
