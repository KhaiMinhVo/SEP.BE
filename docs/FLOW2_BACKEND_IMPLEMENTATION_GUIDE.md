# FLOW2_BACKEND_IMPLEMENTATION_GUIDE.md
# InfluencerMatch AI — Backend Implementation Guide for Flow 2

## 1. Purpose

Tài liệu này mô tả **cách Backend nên hiểu và triển khai Flow 2 — Creator Discovery & Public Intelligence**.

Mục tiêu của tài liệu không phải mô tả UI hay vẽ flow, mà là trả lời rõ:

- Backend cần làm những chức năng nào.
- Dữ liệu nào được lấy từ đâu.
- Service nào chịu trách nhiệm gì.
- Khi nào crawl.
- Khi nào dùng M2 hiện có.
- Khi nào refresh.
- Khi nào dùng AI.
- Business rule nào phải được áp dụng.
- Thành phần nào cần configurable.
- Flow 2 dừng ở đâu và bàn giao gì cho Flow 3.

AI coding agent hoặc Backend developer phải **đọc code hiện tại trước khi implement**, sau đó map tài liệu này vào structure đang có.

---

# 2. Scope của Flow 2

Flow 2 có 3 nhóm chức năng chính:

1. **Creator Acquisition**
   - tìm creator mới từ các nguồn public;
   - lấy username / externalId / profileUrl;
   - đưa vào queue;
   - crawl dữ liệu public;
   - tạo Creator + M2.

2. **M2 Data Maintenance**
   - refresh dữ liệu creator đã có;
   - scheduled refresh;
   - manual refresh;
   - update recent posts;
   - update freshness;
   - update activity status.

3. **Creator Discovery**
   - lấy Campaign Context M3;
   - query M2;
   - hard filter;
   - nếu thiếu exact candidate thì lấy similar candidate trong M2;
   - trả Candidate Pool cho Flow 3.

Flow 2 **không làm final scoring/ranking**.

---

# 3. Backend phải nghiên cứu code hiện tại trước

Trước khi sửa hoặc thêm code, cần kiểm tra:

- `Campaign`
- `CampaignContext`
- `Creator`
- entity/table đang dùng cho M2/PublicMetric
- repository hiện có
- service hiện có
- controller hiện có
- Flyway migrations
- scheduler
- Python crawler
- AI/Gemini integration
- enum
- API response convention
- exception handling
- logging
- security/auth

Sau khi kiểm tra, mỗi component phải được đánh dấu:

- `REUSE`
- `MODIFY`
- `CREATE`
- `DO NOT TOUCH`

Không tạo class mới nếu code đã có class tương đương.

---

# 4. Core Domain Responsibilities

## 4.1 Creator

`Creator` là identity registry nội bộ.

Nó không phải catalog để Admin nhập thủ công.

Identity nên được xác định bằng:

```text
platform + externalId
```

Nên có unique constraint tương ứng.

Creator giữ các thông tin ổn định như:

- platform
- externalId
- username
- displayName
- profileUrl
- avatarUrl
- status / active
- createdAt
- updatedAt

Không nên nhét toàn bộ public metrics thay đổi thường xuyên vào Creator.

---

## 4.2 CreatorPublicSnapshot / PublicMetric

Đây là M2.

M2 lưu public intelligence tại một thời điểm.

Target conceptual fields:

```text
publicMetricId
creatorId

followers
avgViews
avgLikes
avgComments
avgShares
engagementRate

niche
category
creatorType
creatorTypeConfidence
contentSummary
contentSignals

location
contact
urlProfile

collectedAt
freshnessStatus
dataConfidence
activityStatus
lastActivityAt
source
```

AI phải kiểm tra schema hiện tại trước khi thêm field mới.

---

## 4.3 CreatorRecentPost

Nên có entity/table riêng để lưu recent content evidence.

Target conceptual fields:

```text
recentPostId
creatorId

platformPostId
postUrl
caption
hashtags

views
likes
comments
shares

postedAt
thumbnailUrl
contentType
collectedAt
```

Không tạo duplicate mỗi lần refresh.

Nên dùng unique key phù hợp với platform.

---

# 5. Creator Acquisition

Creator Acquisition là quá trình bổ sung creator mới vào M2.

Không nên để Admin tự nhập từng creator.

Backend cần hỗ trợ một abstraction kiểu:

```text
SeedQuery
```

SeedQuery có thể có:

```text
sourceType
query
platform
enabled
priority
```

`sourceType` có thể gồm:

- TRENDING
- KEYWORD
- HASHTAG
- CATEGORY
- EXISTING_CREATOR_EXPANSION

Backend không cần hard-code ngành nghề cụ thể.

Ví dụ thêm Beauty, Food, Tech, Travel phải là thay config/data chứ không sửa source code crawler.

---

# 6. Username Discovery

Acquisition nên tách thành 2 giai đoạn:

### Giai đoạn 1: tìm username

Từ SeedQuery, crawler/search layer lấy:

- username
- externalId nếu có
- profileUrl
- platform

Sau đó deduplicate.

### Giai đoạn 2: crawl intelligence

Username đủ điều kiện mới được đưa vào crawl queue để lấy:

- profile public data
- tối đa 3 recent posts

Việc này giúp tách:

- platform search
- profile extraction

thành 2 responsibility khác nhau.

---

# 7. Crawl Queue và Batch

Có thể dùng rule MVP:

```text
batchSize = 50
```

Khi queue đủ khoảng 50 username thì tạo batch.

Quan trọng:

- `batchSize = 50` không có nghĩa chạy 50 request song song.
- concurrency phải thấp hơn và configurable.

Ví dụ:

```text
batchSize = 50
concurrency = 3–5
```

Backend hoặc crawler worker phải:

- retry hợp lý;
- ghi lỗi;
- không làm fail cả batch vì một creator lỗi.

---

# 8. Crawler Strategy

Crawler nên self-hosted.

Ưu tiên:

1. official/public API nếu có;
2. HTTP/JSON endpoint;
3. HTML/embedded JSON;
4. Playwright fallback.

Có thể sử dụng open-source GitHub repository/library.

Tuy nhiên:

- phải wrap sau adapter/interface;
- business layer không được gọi trực tiếp library cụ thể;
- phải có khả năng thay implementation.

Conceptual interface:

```text
CreatorCrawlerProvider
```

Nên có platform implementation riêng:

- TikTokCrawlerProvider
- InstagramCrawlerProvider
- YouTubeCrawlerProvider

---

# 9. Raw Crawler Contract

Python crawler nên trả một contract ổn định.

Ví dụ:

```json
{
  "creator": {
    "platform": "TIKTOK",
    "external_id": "123456",
    "username": "@creator",
    "display_name": "Creator",
    "profile_url": "https://...",
    "avatar_url": "https://...",
    "followers": 42000,
    "bio": "Skincare reviewer",
    "public_email": null,
    "public_phone": null,
    "location": null
  },
  "recent_posts": [
    {
      "platform_post_id": "P001",
      "post_url": "https://...",
      "caption": "Review sunscreen...",
      "hashtags": ["skincare"],
      "views": 85000,
      "likes": 4200,
      "comments": 170,
      "shares": 95,
      "posted_at": "2026-09-18T11:00:00Z"
    }
  ],
  "source": "TIKTOK_HTTP",
  "collected_at": "2026-09-19T00:00:00Z"
}
```

Spring Boot không cần biết crawler đã dùng HTTP, BeautifulSoup hay Playwright.

---

# 10. Crawl Profile

Cố gắng lấy factual data:

- platform
- externalId
- username
- displayName
- profileUrl
- avatarUrl
- followers
- bio
- publicEmail
- publicPhone
- location nếu source có

Nếu không có thì `null`.

Không dùng AI để bịa factual field.

---

# 11. Crawl 3 Recent Posts

Mỗi creator cố gắng lấy tối đa 3 bài gần nhất.

Mỗi post nên lấy:

- platformPostId
- postUrl
- caption
- hashtags
- views
- likes
- comments
- shares
- postedAt

Optional:

- thumbnailUrl
- contentType
- duration

Nếu platform có pinned posts thì phải dựa vào `postedAt` để tìm bài gần nhất thực sự.

Không mặc định lấy 3 DOM item đầu tiên.

---

# 12. Normalization

Normalization phải là deterministic code.

Ví dụ:

```text
42.3K → 42300
1.2M → 1200000
```

Normalization phụ trách:

- số liệu;
- timestamp;
- URL;
- username;
- hashtag;
- platform enum;
- null handling.

Không dùng AI cho bước này.

---

# 13. Average Metrics

Sau khi có recent posts, hệ thống có thể tính:

- avgViews
- avgLikes
- avgComments
- avgShares

Rule:

Nếu field của một post là `null`, không tự coi là `0`.

Ví dụ chỉ có 2 post có views:

```text
avgViews = (view1 + view2) / 2
```

không chia mặc định cho 3.

Công thức phải được đặt ở một service/component duy nhất.

---

# 14. Engagement Rate

Phải dùng một công thức thống nhất trong hệ thống.

Ví dụ MVP:

```text
ER =
(avgLikes + avgComments + avgShares)
/
followers
* 100
```

Trước khi implement phải kiểm tra project đã có công thức ER chưa.

Nếu có thì reuse.

Nếu chưa có thì tạo logic tập trung.

Không duplicate formula trong nhiều service.

---

# 15. AI Enrichment

AI không phải mapper JSON sang DB.

AI chỉ xử lý semantic data.

Input nên gồm:

- bio;
- caption 3 bài gần nhất;
- hashtags;
- textual content signals;
- một số factual context nếu cần.

AI có thể trả:

- niche;
- category;
- creatorType;
- creatorTypeConfidence;
- contentSignals;
- contentSummary;
- semanticConfidence.

AI không được generate:

- followers;
- views;
- likes;
- comments;
- shares;
- email;
- phone;
- postedAt;
- profileUrl.

AI output phải schema validate trước khi save.

---

# 16. Creator Type

Nên dùng controlled enum:

```text
KOL
KOC
BLOGGER
CONTENT_CREATOR
EXPERT
CELEBRITY
UNKNOWN
```

Không ép mọi creator phải là KOL/KOC/Blogger.

Nếu evidence không đủ:

```text
UNKNOWN
```

Classification nên dựa trên:

- bio;
- recent posts;
- hashtags;
- content pattern;
- follower context;
- review/affiliate signal;
- domain authority signal.

Không dùng rule đơn giản kiểu:

```text
followers > X = KOL
```

---

# 17. Business Rule: M2 là nguồn Discovery chính

Khi Brand search creator, Backend phải query M2 trước.

Không thiết kế Brand Search phụ thuộc bắt buộc vào realtime crawling.

Lý do:

- crawling có thể chậm;
- social platform có thể block;
- ảnh hưởng UX;
- tăng nguy cơ fail demo;
- khó đảm bảo latency.

---

# 18. Discovery dùng M3 + M2

Backend lấy active CampaignContext M3.

Từ M3 build search criteria.

Criteria có thể gồm:

- platform;
- location;
- niche;
- category;
- creatorType;
- follower range;
- engagement;
- creator tier;
- estimated fee nếu có;
- recent activity.

Sau đó query latest usable M2.

---

# 19. Candidate Threshold Rule

Suggested MVP rule:

```text
>= 30 exact eligible candidates
→ dùng trực tiếp

10–29
→ bổ sung Similar Creator từ M2

< 10
→ Similar Creator từ M2
→ optional background targeted acquisition
```

Các số này phải configurable.

Không hard-code rải rác trong code.

---

# 20. Similar Creator Fallback

Nếu exact candidate ít, Flow 2 được phép nới search criteria.

Suggested relaxation order:

1. giữ platform;
2. giữ category/niche;
3. nới follower range;
4. nới location;
5. nới engagement threshold;
6. xét creatorType tương đương.

Similar fallback chỉ dùng để mở rộng Candidate Pool.

Nó không phải final recommendation ranking.

---

# 21. Realtime / Targeted Crawl

Realtime crawl không phải requirement bắt buộc mỗi lần Brand search.

Nếu muốn dùng, nên là:

- background job;
- targeted acquisition;
- chạy khi candidate quá ít;
- cập nhật M2 sau khi crawl xong.

Brand không cần chờ full crawl để nhận initial result.

---

# 22. Quy mô M2

Target MVP:

```text
500–700 ACTIVE/usable creators
```

Đây không phải hard DB limit.

Database có thể chứa nhiều record hơn vì:

- archived creators;
- inactive creators;
- historical creators.

Mục tiêu 500–700 là active usable pool.

---

# 23. Coverage quan trọng hơn số lượng

M2 cần đủ diversity:

- category;
- niche;
- platform;
- location;
- creator tier;
- creator type;
- activity;
- data freshness.

Không nên có 700 creator nhưng phần lớn cùng một category.

---

# 24. Scheduled Refresh

Scheduled refresh chạy định kỳ.

Không cần refresh toàn bộ 500–700 creator mỗi ngày.

Mỗi ngày có thể làm 2 việc:

1. refresh creator cũ;
2. discover creator mới.

Suggested MVP config:

```text
refreshExistingPerDay = 100
discoverNewUsernamesPerDay = 50
```

Các số này phải configurable.

---

# 25. Refresh Priority

Refresh nên theo priority.

Thứ tự gợi ý:

1. creator đang dùng trong active campaign;
2. creator vừa xuất hiện trong Candidate Pool / Recommendation path;
3. creator có M2 stale;
4. creator có M2 aging;
5. creator lâu không được query.

Không cần AI để tính refresh priority.

Rule-based là đủ.

---

# 26. Manual Refresh

Manual refresh và Scheduled refresh phải dùng chung một service.

Ví dụ:

```text
CreatorRefreshService
```

Manual endpoint chỉ trigger service này.

Không duplicate crawling logic.

---

# 27. Freshness

Freshness dựa vào `collectedAt`.

Suggested MVP config:

```text
0–7 ngày   = FRESH
8–30 ngày  = AGING
>30 ngày   = STALE
```

Đây là configurable rule.

Refresh fail không được xóa snapshot tốt gần nhất.

---

# 28. Pre-Recommendation Freshness Check

Trước khi Flow 3 sử dụng Candidate Pool, Backend nên kiểm tra freshness của các creator thực sự chuẩn bị được recommendation.

Không refresh toàn bộ M2.

Chỉ refresh stale candidate quan trọng.

Sau refresh:

- update M2;
- update recent posts;
- update activityStatus;
- update dataConfidence.

---

# 29. Creator Activity Status

Suggested enum:

```text
ACTIVE
DORMANT
INACTIVE
UNAVAILABLE
ARCHIVED
```

Suggested configurable thresholds:

```text
0–14 ngày không post
→ ACTIVE

15–30 ngày
→ DORMANT

31–60 ngày
→ INACTIVE

profile deleted/private/unreachable
→ UNAVAILABLE
```

Không được deactivate ngay chỉ vì 15 ngày không post.

---

# 30. Creator Lifecycle

### ACTIVE
Có thể tham gia Discovery bình thường.

### DORMANT
Vẫn có thể được xét nhưng confidence thấp hơn.

### INACTIVE
Không nên được ưu tiên trong Discovery.

### UNAVAILABLE
Không nên được đưa vào Candidate Pool.

### ARCHIVED
Không còn nằm trong active pool nhưng giữ historical relationship.

---

# 31. Không hard delete Creator có history

Nếu creator đã có:

- campaign;
- recommendation;
- shortlist;
- relationship;
- collaboration;
- M1 historical evidence;

thì không nên hard delete.

Nên:

```text
active = false
status = ARCHIVED
```

để giữ lịch sử và audit.

---

# 32. Active Pool khoảng 500–700 Creator

Nếu muốn duy trì active pool ở mức này, Backend có thể archive low-utility creator khi thêm creator mới.

Low-utility signal có thể gồm:

- inactive lâu;
- không nằm trong campaign;
- không được search lâu;
- không được recommendation lâu;
- no current collaboration;
- low data quality.

Không cần machine learning.

Rule-based là đủ cho MVP.

---

# 33. Admin Responsibility

Admin không maintain Creator Catalog thủ công.

Admin chỉ xử lý exception:

- repeated crawler failure;
- duplicate creator;
- profile unavailable nhiều lần;
- suspicious data;
- manual retry;
- manual archive.

Không gửi notification cho Admin chỉ vì creator 15 ngày không post.

Notification nên dành cho actionable exception.

---

# 34. Error Handling

Backend phải xử lý ít nhất:

- crawler timeout;
- crawler 404;
- account private;
- account deleted;
- insufficient posts;
- missing metrics;
- invalid AI JSON;
- AI timeout;
- duplicate creator;
- duplicate post;
- DB failure.

Refresh fail:

- không xóa snapshot cũ;
- ghi refresh failure;
- giữ last successful data;
- update freshness theo thời gian.

---

# 35. Suggested Backend Services

Chỉ tạo nếu current code chưa có equivalent.

## CreatorDiscoveryService
Chịu trách nhiệm:

- lấy M3;
- build search criteria;
- query M2;
- hard filter;
- similar fallback;
- tạo Candidate Pool.

## CreatorRefreshService
Chịu trách nhiệm orchestration:

- gọi crawler;
- normalize;
- validate;
- enrich;
- resolve identity;
- save recent posts;
- save M2;
- update freshness/activity.

## CreatorIdentityService
Chịu trách nhiệm:

- find by platform + externalId;
- create Creator nếu chưa có;
- prevent duplicate.

## CreatorNormalizationService
Chịu trách nhiệm:

- normalize factual data;
- calculate averages;
- calculate ER.

## CreatorEnrichmentService
Chịu trách nhiệm:

- gọi Gemini/AI;
- classify niche/category/creatorType;
- validate output.

## CreatorAcquisitionService
Chịu trách nhiệm:

- process SeedQuery;
- collect usernames;
- deduplicate;
- enqueue crawl jobs.

## CreatorLifecycleService
Chịu trách nhiệm:

- activity status;
- archive rules;
- unavailable handling.

---

# 36. Suggested Repositories

Chỉ tạo nếu chưa có:

- CreatorRepository
- CreatorPublicSnapshotRepository
- CreatorRecentPostRepository
- SeedQueryRepository nếu SeedQuery persist
- CrawlJobRepository nếu crawl queue persist

Không nhất thiết persist queue trong MVP nếu existing architecture dùng in-memory/background job phù hợp.

---

# 37. Suggested Controllers

## CreatorDiscoveryController

Phụ trách API cho Brand:

- list/search candidate;
- get creator detail.

## CreatorRefreshController

Phụ trách:

- manual refresh;
- refresh status nếu cần.

Admin exception APIs có thể nằm trong Admin module hiện tại thay vì tạo controller riêng.

---

# 38. Configuration cần tập trung

Các giá trị sau không nên hard-code rải rác:

```text
candidateExactThreshold
candidateCriticalThreshold

crawlBatchSize
crawlConcurrency

refreshExistingPerDay
discoverNewUsernamesPerDay

freshDays
agingDays
inactiveDays
archiveDays

maxRecentPosts

retryCount
retryDelay

activePoolTargetMin
activePoolTargetMax
```

Nên đặt ở:

- `application.yml`;
- config properties;
- hoặc Recommendation/Creator settings nếu Admin cần chỉnh.

---

# 39. API Response nên trả gì cho Discovery

Candidate response nên có các field đủ để FE hiển thị:

```text
creatorId
platform
username
displayName
profileUrl
avatarUrl

followers
avgViews
engagementRate

niche
category
creatorType

location
contentSummary

freshnessStatus
dataConfidence
activityStatus
lastActivityAt
```

Không cần trả Flow 3 score ở đây.

---

# 40. Flow 2 Output

Flow 2 output là:

```text
Candidate Pool
```

Mỗi candidate phải có:

- creator identity;
- latest usable M2;
- activity status;
- freshness;
- public intelligence cần thiết.

Flow 3 mới nhận Candidate Pool để:

- scoring;
- ranking;
- confidence;
- explanation;
- Top-N.

---

# 41. Suggested Implementation Order

## Phase 1
Inspect code hiện tại.

## Phase 2
Chốt/reuse:
- Creator
- M2 entity
- RecentPost entity

## Phase 3
Chốt crawler contract.

## Phase 4
Implement factual crawl + normalization, chưa dùng AI.

## Phase 5
Persist recent posts + M2.

## Phase 6
Thêm AI enrichment.

## Phase 7
Thêm Scheduled Refresh.

## Phase 8
Thêm Manual Refresh.

## Phase 9
Thêm Creator Acquisition.

## Phase 10
Thêm M3 + M2 Discovery.

## Phase 11
Thêm Similar M2 fallback.

## Phase 12
Thêm pre-recommendation freshness check.

## Phase 13
Thêm lifecycle/archive/admin exception handling.

---

# 42. Backend phải báo cáo trước khi code

Trước khi implement, Coding AI phải output:

## Current Components
Những gì đang có.

## Reusable Components
Những gì có thể reuse.

## Missing Components
Những gì còn thiếu.

## Conflicts
Điểm nào tài liệu này không khớp code hiện tại.

## Proposed Files to Modify
Danh sách file sửa.

## Proposed Files to Create
Danh sách file mới.

## Migration Impact
Table/column/index/enum cần thay đổi.

## Implementation Plan
Thứ tự implement.

Sau bước này mới bắt đầu code.

---

# 43. Business Rules cần nhớ

1. M2 là nguồn Discovery chính.
2. Không realtime crawl bắt buộc cho mỗi Brand search.
3. Thiếu exact candidate thì Similar M2 trước.
4. Targeted crawl là background/enhancement.
5. 500–700 là active pool target, không phải DB hard limit.
6. Acquisition phải đa nguồn seed.
7. Không hard-code ngành nghề.
8. Crawl theo batch nhưng concurrency giới hạn.
9. Lấy tối đa 3 recent posts cho MVP.
10. Factual normalize bằng code.
11. AI chỉ semantic enrichment.
12. Creator Type dùng controlled enum.
13. Scheduled + Manual dùng chung refresh pipeline.
14. Refresh existing + acquire new đều cần trong M2 maintenance.
15. Không deactivate chỉ vì 15 ngày không post.
16. Không hard delete creator có history.
17. Admin chỉ quản lý exception.
18. Flow 2 không làm final ranking.
19. Flow 2 output Candidate Pool.
20. Flow 3 mới recommendation.

---

# 44. Prompt dùng cho Coding AI

> Read `FLOW2_BACKEND_IMPLEMENTATION_GUIDE.md` completely. Then inspect the existing backend and crawler code before implementing anything. Do not redesign the whole system. First identify what already exists for Campaign, CampaignContext, Creator, PublicMetric/M2, RecentPost, crawler integration, scheduler, AI/Gemini, repositories, controllers, Flyway and configuration. Map each requirement in this document to REUSE, MODIFY, CREATE or DO NOT TOUCH. Then report database migration impact and a phased implementation plan. Treat all numeric thresholds in this document as configurable MVP rules, not immutable domain rules. Only after that analysis should implementation begin.
