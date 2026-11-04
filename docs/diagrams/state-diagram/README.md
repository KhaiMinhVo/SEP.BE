# InfluencerMatch — State Diagrams theo tài liệu

Ngày lập: **26/10/2026**. Căn cứ: Capstone Project Document **FA26SE287 / GFA26SE108** và Capstone Project Register người dùng cung cấp. Đây là bộ **thiết kế nghiệp vụ SRS/SDD**, không phải lời khẳng định mọi transition đã được lập trình. Bộ package/class diagram ở thư mục cha mô tả code hiện tại; bộ state diagram này ưu tiên yêu cầu tài liệu.

[Mở bộ xem sơ đồ](index.html) · [Quay lại package/class diagrams](../index.html)

## Các hình chính

1. [Campaign](rendered/01-state-campaign.svg) — [PNG](rendered/01-state-campaign.png) · [PlantUML](01-state-campaign.puml). DRAFT, ACTIVE, COMPLETED, CANCELLED, ARCHIVED. Dùng trong phần Campaign Lifecycle.
2. [Brand–Creator Relationship](rendered/02-state-relationship.svg) — [PNG](rendered/02-state-relationship.png) · [PlantUML](02-state-relationship.puml). SHORTLISTED, CONTACTED, COLLABORATING, COMPLETED, NOT_INTERESTED. Dùng cho CRM trong một Campaign.
3. [Collaboration & Outcome](rendered/03-state-collaboration.svg) — [PNG](rendered/03-state-collaboration.png) · [PlantUML](03-state-collaboration.puml). IN_PROGRESS, COMPLETED, CANCELLED. Mô hình hợp tác cơ bản, ghi nhận nội dung/kết quả và review.
4. [User Account](rendered/04-state-user-account.svg) — [PNG](rendered/04-state-user-account.png) · [PlantUML](04-state-user-account.puml). ACTIVE, LOCKED, SUSPENDED theo mô tả màn hình quản trị. Trạng thái tài khoản tách biệt role, OTP và phiên đăng nhập.
5. [Creator Claim](rendered/05-state-creator-claim.svg) — [PNG](rendered/05-state-creator-claim.png) · [PlantUML](05-state-creator-claim.puml). PENDING, APPROVED, REJECTED, REVOKED cho một yêu cầu claim thủ công. Bản đăng ký yêu cầu claim; chi tiết được bổ sung từ code.
6. [Subscription](rendered/06-state-subscription.svg) — [PNG](rendered/06-state-subscription.png) · [PlantUML](06-state-subscription.puml). PENDING, ACTIVE, EXPIRED, CANCELLED. Gói trả phí chờ xác nhận thanh toán; quota là điều kiện sử dụng, không phải trạng thái hết hạn.
7. [Payment](rendered/07-state-payment.svg) — [PNG](rendered/07-state-payment.png) · [PlantUML](07-state-payment.puml). PENDING, SUCCESS, FAILED, REFUNDED theo tên trong màn hình Transaction. Chỉ dành cho tiền gói dịch vụ.
8. [Creator Data Collection Job](rendered/08-state-collection-job.svg) — [PNG](rendered/08-state-collection-job.png) · [PlantUML](08-state-collection-job.puml). PENDING, RUNNING, SUCCEEDED, RATE_LIMITED, FAILED, CANCELLED. Tách vòng đời job khỏi freshness của snapshot.
9. [Recommendation Run](rendered/09-state-recommendation-run.svg) — [PNG](rendered/09-state-recommendation-run.png) · [PlantUML](09-state-recommendation-run.puml). QUEUED, RUNNING, SUCCEEDED, FAILED. Mô tả trạng thái một lần chạy, không biến từng bước thuật toán thành trạng thái Campaign.
10. [Post-Campaign Learning Job](rendered/10-state-post-campaign-learning.svg) — [PNG](rendered/10-state-post-campaign-learning.png) · [PlantUML](10-state-post-campaign-learning.puml). PENDING, VALIDATING, WAITING_EVIDENCE, PROCESSING, SUCCEEDED, FAILED. Thiếu dữ liệu hợp lệ thì chờ hoặc chỉ xử lý phần bằng chứng có thật.

## Hai hình phụ lục cho phạm vi mở rộng

- [A1 — Content Approval Collaboration](rendered/A1-state-content-collaboration.svg) — [PNG](rendered/A1-state-content-collaboration.png) · [PlantUML](A1-state-content-collaboration.puml). ACCEPTED → CONTENT_IN_PROGRESS → CONTENT_REVIEW → APPROVED → POSTED → COMPLETED; có vòng yêu cầu sửa và hủy trước khi đăng. Căn cứ phần Brand screens 22–23, không phải state machine hiện tại trong Java. Nếu nhóm chọn workflow này, A1 **thay thế** hình 03 ở mức thiết kế chi tiết.
- [A2 — Collaboration Invitation](rendered/A2-state-collaboration-invitation.svg) — [PNG](rendered/A2-state-collaboration-invitation.png) · [PlantUML](A2-state-collaboration-invitation.puml). PENDING → ACCEPTED / DECLINED / EXPIRED / CANCELLED theo Brand screen 21. Dùng cùng A1 khi có cơ chế nhận lời mời trong hệ thống. Đây không phải lời mời claim kênh hay lời mời đăng ký Creator.

Hai hình này là phương án theo phần màn hình, được tách riêng vì phần giới hạn MVP loại trừ marketplace hai chiều. Không mặc định đưa cả hai vào phạm vi đã thống nhất.

## Cách đọc và đưa vào Report 4

- Chấm đen là điểm khởi tạo một vòng đời; vòng tròn đồng tâm là kết thúc vòng đời đó, **không phải xóa bản ghi**.
- Nhãn mũi tên: `event [guard] / effect` — sự kiện, điều kiện cho phép, tác động khi chuyển trạng thái.
- Dòng trong ô như `recordInteraction / update notes` là xử lý nội bộ, không đổi trạng thái. `do / ...` là hoạt động khi đang ở trạng thái đó.
- Enum/tên trạng thái được giữ tiếng Anh để dễ đồng bộ database và source code. Hình chỉ giữ tiêu đề, trạng thái và nhãn chuyển trạng thái; không có ô ghi chú, khung chú giải hoặc caption dưới hình. Căn cứ, phân loại thiết kế và giải thích được giữ trong tài liệu này và `manifest.json` để tra cứu riêng.
- Các mũi tên thể hiện thiết kế được chọn cho bộ hình này; **tài liệu chưa cung cấp đầy đủ transition matrix**. Guard, retry policy, actor/event cụ thể và side effect được mô hình hóa thành đề xuất SDD, không phải trích nguyên văn các business rule đã phê duyệt.
- Những trạng thái không có mũi tên tới final có thể tiếp tục nhận event: tài khoản có thể được mở khóa, claim APPROVED có thể bị revoke, collaboration COMPLETED còn nhận review. Không ép kết thúc máy trạng thái quá sớm.
- Mỗi hình mô tả một đối tượng hoặc một job. Các đối tượng có trạng thái độc lập; ví dụ Payment.SUCCESS là sự kiện kích hoạt Subscription.ACTIVE chứ không phải cùng một trường status.
- Word/Slides: chèn PNG khi cần tương thích, dùng SVG khi muốn phóng to vẫn rõ. Không ghép tất cả các state machine thành một flowchart hệ thống.

## Quy tắc lựa chọn khi tài liệu mâu thuẫn

**Campaign:** FE-02 mô tả draft/activate/complete; UC17 có Archived; màn hình Campaign List có CANCELLED. Hình 01 thể hiện cả hai mục đích **hủy** và **lưu trữ**, với các transition archive/cancel là đề xuất cần đồng bộ vào SDD. Lưu trữ CampaignContext theo FE-10.7 không tự động đổi Campaign.status thành ARCHIVED.

**CRM:** FE-07.4 và UC36 liệt kê SHORTLISTED, CONTACTED, COLLABORATING, COMPLETED, NOT_INTERESTED. Brand screen 20 lại dùng NEW, CONTACTED, REPLIED, INTERESTED, PARTNER, PAUSED. Hình 02 ưu tiên FE/UC, không trộn hai enum. `INTERESTED` trong code là một chi tiết bổ sung chưa có trong tập trạng thái FE-07.4. Relationship được theo dõi trong một campaign; việc bắt đầu tracking không có nghĩa chỉ thêm shortlist item đã tự tạo relationship.

**Creator và payment:** phần Limitations/Exclusions của Capstone Document vẫn có nội dung không cho Creator đăng nhập và loại trừ thanh toán; nhưng Capstone Register có Creator authentication/claim, bốn role và Subscription & Payment. UC47 trong Capstone Document cũng nói gói trả phí chỉ kích hoạt sau sandbox payment. Bộ hình giữ claim và subscription payment theo các chức năng đó; không thêm chuyển tiền cho Creator, escrow hoặc settlement phí hợp tác.

**Content approval:** phần màn hình chứa invitation acceptance và duyệt nội dung, trong khi FE-08 chỉ yêu cầu ghi nhận hợp tác, outcome và review; EX-02 loại trừ marketplace nhận lời mời trực tiếp. Hình 03 là luồng cơ bản; A1/A2 là phương án riêng. UC40 nói quản lý contract nhưng EX-09 loại trừ contract workflow; không tự thêm vòng đời hợp đồng vào bộ hình này.

## Giả định thiết kế cần ghi trong SDD

1. **Đóng/hủy campaign:** điều kiện hoàn tất là đã xử lý thông tin đóng chiến dịch bắt buộc; hủy khi các nghĩa vụ hợp tác đang mở đã được giải quyết. Đây là guard đề xuất, không khẳng định mọi KPI phải được nhập. Không tự cascade đổi tất cả Collaboration khi đổi Campaign; quy tắc đó cần được xác định riêng.
2. **Archiving:** cho phép archive draft, completed hoặc cancelled campaign. Không reopen campaign đã kết thúc trong mô hình này. Dữ liệu lịch sử giữ nguyên.
3. **CRM:** cho phép quay từ NOT_INTERESTED sang CONTACTED nếu tiếp tục liên hệ và campaign còn phù hợp; collaboration bị hủy chuyển relationship sang NOT_INTERESTED trong thiết kế đề xuất. Những chính sách này không được tài liệu quy định chi tiết.
4. **Collaboration:** nhãn IN_PROGRESS/COMPLETED được chuẩn hóa từ FE-08 và code; nhánh CANCELLED là chính sách thiết kế. Review là thao tác sau hoàn tất, không phải điều kiện bắt buộc để hợp tác được hoàn tất. Không tạo trạng thái REVIEWED trong enum Collaboration chỉ vì đã có review.
5. **Account:** SUSPENDED theo màn hình chưa có định nghĩa riêng so với LOCKED; mô hình dùng suspended cho đình chỉ cần xử lý và restored cho khôi phục. Không coi SUSPENDED và DISABLED trong code tự động tương đương. Việc vô hiệu phiên khi khóa là tác động bảo mật đề xuất, phù hợp với cách code hiện tại làm.
6. **Subscription:** mô hình chọn hủy có hiệu lực ngay; tài liệu chưa xác định hủy cuối kỳ, gia hạn cùng bản ghi hay tạo bản ghi mới. Hình dùng bản ghi mới cho gia hạn/nâng gói để giữ lịch sử. Hết hạn dựa trên thời hạn gói, không phải số quota còn lại.
7. **Payment:** xác minh callback trước settlement; lỗi callback hoặc xung đột kích hoạt không được coi là đã trả tiền thành công. Hình dùng SUCCESS theo SRS; retry tạo Payment khác. Refund cần xác nhận từ gateway; chính sách thu hồi entitlement sau refund phải được xác định, không mặc định hoàn tiền đồng nghĩa xóa subscription.
8. **Collection:** các trạng thái PENDING/RUNNING/SUCCEEDED được bổ sung để mô hình hóa queue/worker. RATE_LIMITED/FAILED và cancel pending/retry có căn cứ màn hình. Retry dùng cùng job và ghi từng attempt trong thiết kế này; chưa có retry budget/thời gian cụ thể để gán con số.
9. **Recommendation:** vòng đời QUEUED/RUNNING/SUCCEEDED/FAILED là đề xuất cho RecommendationRun. Không có M1 không phải lỗi; không tìm thấy creator phù hợp có thể là SUCCEEDED với tập kết quả rỗng. Lần chạy mới giữ phiên bản tham chiếu mới; không sửa lịch sử lần chạy cũ.
10. **Learning:** các trạng thái job là đề xuất, không phải enum đã có. Không đủ bằng chứng hợp lệ thì WAITING_EVIDENCE; không bắt buộc mọi trường dữ liệu tùy chọn. Chỉ tạo M1 từ bằng chứng đã kiểm tra, cập nhật phần learned M4 nếu có căn cứ và không ghi đè explicit profile. Retry không được nhân đôi bằng chứng đã commit.
11. **A1/A2:** trạng thái lấy từ màn hình; điều kiện chấp nhận, hết hạn, hủy và các cạnh quay lại sửa nội dung là thiết kế suy ra. Không mô hình hủy sau POSTED khi tài liệu chưa có chính sách xử lý nội dung đã công khai.

## Đối chiếu code hiện tại — không đồng nhất thiết kế với triển khai

- `campaign/enums/CampaignStatus.java`: DRAFT → READY_FOR_DISCOVERY → ACTIVE → COMPLETED; chỉ DRAFT → ARCHIVED. Không có CANCELLED; không có COMPLETED → ARCHIVED. `CampaignService` tạo M3 khi chuyển READY_FOR_DISCOVERY, trong khi tài liệu muốn versioned context khi discovery/recommendation bắt đầu.
- `relationship/service/RelationshipServiceImpl.java`: `updateStage` đặt trực tiếp enum được yêu cầu sau khi kiểm tra quyền/sở hữu, chưa ép đúng transition matrix của hình 02. Code cũng cho INTERESTED.
- `collaboration/service/CollaborationService.java`: create tạo trực tiếp IN_PROGRESS khi Campaign ACTIVE và relationship CONTACTED/INTERESTED; recordOutcome chuyển COMPLETED và cập nhật relationship; review yêu cầu đã completed và có outcome. Enum có PLANNED/CANCELLED nhưng service này chưa có endpoint chuyển các trạng thái đó. Các stage duyệt nội dung A1 chưa triển khai.
- `user/enums/UserStatus.java`: ACTIVE, LOCKED, DISABLED; không có SUSPENDED. `AdminUserService.changeStatus` kiểm tra quyền, bảo vệ admin cuối cùng và thu hồi phiên, nhưng không dùng đúng matrix SRS trong hình 04.
- `creator/enums/CreatorClaimStatus.java` và `CreatorClaimService.java`: PENDING → APPROVED/REJECTED; APPROVED → REVOKED với kiểm tra quyền sở hữu hiện tại. Hình 05 chỉ mô tả manual claim. OAuth có phiên và quy tắc riêng; không giả định OAuth phải tạo cùng một manual claim.
- `billing/enums/PaymentStatus.java`: dùng **PAID**, không phải SUCCESS. `PaymentSettlementService` xử lý PENDING → PAID/FAILED cùng Subscription ACTIVE/CANCELLED và callback lặp. REFUNDED có trong enum nhưng chưa thấy workflow refund hoàn chỉnh như hình 07.
- `SubscriptionStatus` có EXPIRED, nhưng chưa thấy scheduler chuyển trạng thái hết hạn trong các service đã đối chiếu. Có status trong enum không chứng minh chuyển trạng thái tự động đã chạy.
- Chưa có đầy đủ các entity/job/service cho Data Collection Job, RecommendationRun và Post-Campaign Learning như hình 08–10. `CreatorDiscoveryService` đang thực hiện hybrid search, không phải toàn bộ engine recommendation M1–M4.
- `creator.model.CreatorInvitation` là lời mời claim kênh, `auth.model.CreatorRegistrationInvitation` là lời mời đăng ký. Không dùng chúng làm bằng chứng cho workflow collaboration invitation A2.

## Tái tạo

Từ thư mục gốc repository:

```powershell
python docs/diagrams/state-diagram/generate_states.py
& docs/diagrams/render.ps1 -PlantUmlJar target/uml-tools/plantuml.jar -JavaExecutable 'C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot\bin\java.exe' -DiagramDirectory docs/diagrams/state-diagram
```

Python chỉ dùng standard library. PlantUML render PNG/SVG trên máy, không gửi tài liệu/code lên server bên ngoài. `manifest.json` lưu danh mục, nguồn và phân loại từng hình. Muốn chỉnh nội dung lâu dài, sửa trong `generate_states.py`; chỉnh `.puml` trực tiếp sẽ bị ghi đè khi chạy lại generator.
