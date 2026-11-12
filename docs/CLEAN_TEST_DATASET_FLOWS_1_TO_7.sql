-- ====================================================================================================
-- INFLUENCERMATCH AI - CLEAN TEST DATASET (FLOWS 1 - 7)
-- ====================================================================================================
-- Mục đích: File nạp dữ liệu mẫu sạch, nhất quán và liên kết đầy đủ quan hệ giữa các thực thể
-- phục vụ Frontend (FE) và QA test trực tiếp trên Swagger UI (http://localhost:8080/api/v1/swagger-ui/index.html).
--
-- Tất cả mật khẩu người dùng mẫu: Password123!
-- BCrypt Hash: $2a$10$aho.VNRalTCL7ZLKHwezT.Rk/4a91pTybHNw1bJC1r9KKX6cSZnCa
-- ====================================================================================================

BEGIN;

-- ----------------------------------------------------------------------------------------------------
-- 1. FLOW 7: GÓI DỊCH VỤ (SUBSCRIPTION PLANS)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO plan (
    id, plan_name, price, currency, duration_days,
    campaign_quota, ai_recommendation_quota, refresh_quota,
    description, status, created_at
) VALUES
(
    '11111111-1111-1111-1111-111111111101',
    'FREE',
    0.00,
    'VND',
    30,
    1,
    5,
    5,
    'Gói trải nghiệm miễn phí dành cho Brand mới làm quen với hệ thống',
    'ACTIVE',
    CURRENT_TIMESTAMP
),
(
    '11111111-1111-1111-1111-111111111102',
    'PRO',
    1500000.00,
    'VND',
    30,
    10,
    100,
    50,
    'Gói chuyên nghiệp cho doanh nghiệp vừa và nhỏ (SME) đẩy mạnh chiến dịch Influencer',
    'ACTIVE',
    CURRENT_TIMESTAMP
),
(
    '11111111-1111-1111-1111-111111111103',
    'ENTERPRISE',
    5000000.00,
    'VND',
    30,
    50,
    500,
    200,
    'Gói mở rộng không giới hạn cho Brand lớn và Marketing Agency đa thương hiệu',
    'ACTIVE',
    CURRENT_TIMESTAMP
)
ON CONFLICT (plan_name) DO UPDATE SET
    price = EXCLUDED.price,
    campaign_quota = EXCLUDED.campaign_quota,
    ai_recommendation_quota = EXCLUDED.ai_recommendation_quota,
    refresh_quota = EXCLUDED.refresh_quota,
    status = EXCLUDED.status;

-- ----------------------------------------------------------------------------------------------------
-- 2. TÀI KHOẢN NGƯỜI DÙNG CHUẨN (USERS FOR AUTHENTICATION)
-- Tất cả mật khẩu đăng nhập: Password123!
-- ----------------------------------------------------------------------------------------------------
INSERT INTO "user" (
    id, email, password_hash, full_name, role, status, email_verified, created_at, updated_at
) VALUES
-- Admin User
(
    '550e8400-e29b-41d4-a716-446655440000',
    'admin@influencermatch.com',
    '$2a$10$aho.VNRalTCL7ZLKHwezT.Rk/4a91pTybHNw1bJC1r9KKX6cSZnCa',
    'System Administrator',
    'ADMIN',
    'ACTIVE',
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
-- Brand User (Acme Cosmetics)
(
    '22222222-2222-2222-2222-222222222201',
    'brand@acme.com',
    '$2a$10$aho.VNRalTCL7ZLKHwezT.Rk/4a91pTybHNw1bJC1r9KKX6cSZnCa',
    'Acme Brand Manager',
    'BRAND',
    'ACTIVE',
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
-- Creator User 1 (Glow Beauty - TikTok KOC)
(
    '33333333-3333-3333-3333-333333333301',
    'koc.glow@creator.vn',
    '$2a$10$aho.VNRalTCL7ZLKHwezT.Rk/4a91pTybHNw1bJC1r9KKX6cSZnCa',
    'Nguyễn Diệu Linh (Glow Beauty)',
    'CREATOR',
    'ACTIVE',
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
-- Creator User 2 (Tech Lab VN - YouTube KOC)
(
    '33333333-3333-3333-3333-333333333302',
    'koc.tech@creator.vn',
    '$2a$10$aho.VNRalTCL7ZLKHwezT.Rk/4a91pTybHNw1bJC1r9KKX6cSZnCa',
    'Trần Anh Tuấn (Tech Lab VN)',
    'CREATOR',
    'ACTIVE',
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
-- Data Manager User
(
    '44444444-4444-4444-4444-444444444401',
    'datamanager@influencermatch.com',
    '$2a$10$aho.VNRalTCL7ZLKHwezT.Rk/4a91pTybHNw1bJC1r9KKX6cSZnCa',
    'Data Operations Lead',
    'DATA_MANAGER',
    'ACTIVE',
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (email) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    email_verified = TRUE,
    status = 'ACTIVE';

-- ----------------------------------------------------------------------------------------------------
-- 3. FLOW 1: HỒ SƠ THƯƠNG HIỆU & BỐI CẢNH AI M4 (BRAND PROFILE & M4 CONTEXT)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO brand_profile (
    id, user_id, business_name, industry, product_categories,
    website, location, target_markets, target_audiences,
    brand_tone, preferred_platforms, description, created_at, updated_at
) VALUES (
    '22222222-2222-2222-2222-222222222211',
    '22222222-2222-2222-2222-222222222201',
    'Acme Cosmetics Vietnam',
    'BEAUTY',
    ARRAY['SKINCARE', 'COSMETICS', 'SUNSCREEN'],
    'https://acmecosmetics.vn',
    'Ho Chi Minh City, Vietnam',
    ARRAY['VIETNAM'],
    ARRAY['FEMALE_18_28', 'OFFICE_WORKERS', 'GEN_Z'],
    'MODERN_YOUTHFUL_TRUSTWORTHY',
    ARRAY['TIKTOK', 'YOUTUBE', 'INSTAGRAM'],
    'Thương hiệu mỹ phẩm thuần chay dưỡng sáng và chống nắng bảo vệ làn da phụ nữ Việt Nam.',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO brand_context (
    id, brand_profile_id, context_version, profile_context,
    learned_patterns, evidence_refs, created_at, updated_at
) VALUES (
    '22222222-2222-2222-2222-222222222221',
    '22222222-2222-2222-2222-222222222211',
    1,
    '{"businessName": "Acme Cosmetics Vietnam", "industry": "BEAUTY", "preferredPlatforms": ["TIKTOK", "YOUTUBE"]}'::jsonb,
    '{"preferredEngagementRate": 0.045, "preferredTone": "AUTHENTIC_REVIEW"}'::jsonb,
    '[]'::jsonb,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (brand_profile_id) DO NOTHING;

-- Subscription PRO đang hoạt động cho Acme Cosmetics
INSERT INTO subscription (
    id, brand_profile_id, plan_id, start_date, expiration_date,
    status, used_campaign_quota, used_ai_quota, used_refresh_quota
) VALUES (
    '22222222-2222-2222-2222-222222222231',
    '22222222-2222-2222-2222-222222222211',
    '11111111-1111-1111-1111-111111111102',
    CURRENT_DATE - INTERVAL '5 days',
    CURRENT_DATE + INTERVAL '25 days',
    'ACTIVE',
    1,
    1,
    0
)
ON CONFLICT (id) DO NOTHING;

-- ----------------------------------------------------------------------------------------------------
-- 4. FLOW 1: CHIẾN DỊCH QUẢNG BÁ & BỐI CẢNH AI M3 (CAMPAIGN & M3 CONTEXT)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO campaign (
    id, brand_profile_id, name, product_service, objective,
    target_audiences, platforms, niches, budget_min, budget_max,
    currency, follower_min, follower_max, engagement_rate_min,
    start_date, end_date, status, created_at, updated_at
) VALUES (
    '22222222-2222-2222-2222-222222222241',
    '22222222-2222-2222-2222-222222222211',
    'Summer Glow 2026 - Kem Chống Nắng Dưỡng Trắng',
    'Acme Tone-up Daily Sunscreen SPF50+ PA++++',
    'AWARENESS',
    ARRAY['Nữ 18-28', 'Dân văn phòng', 'Học sinh sinh viên'],
    ARRAY['TIKTOK', 'YOUTUBE'],
    ARRAY['BEAUTY', 'LIFESTYLE', 'SKINCARE'],
    30000000.00,
    80000000.00,
    'VND',
    50000,
    1000000,
    0.0350,
    CURRENT_DATE,
    CURRENT_DATE + INTERVAL '60 days',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO campaign_context (
    id, campaign_id, context_version, context_data, active, archived_at, created_at, updated_at
) VALUES (
    '22222222-2222-2222-2222-222222222251',
    '22222222-2222-2222-2222-222222222241',
    1,
    '{"brief": "Chiến dịch quảng bá kem chống nắng nâng tông mùa hè 2026, tập trung vào video review thực tế ngoài trời, kiểm tra độ kiềm dầu và chống trôi nước.", "targetNiches": ["BEAUTY", "SKINCARE"]}'::jsonb,
    TRUE,
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;

-- ----------------------------------------------------------------------------------------------------
-- 5. FLOW 4: ĐỊNH DANH MASTER KOC (CREATOR MASTER M1)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO creator_master (id, user_id, canonical_name, status, notes, created_at, updated_at)
VALUES
(
    '33333333-3333-3333-3333-333333333311',
    '33333333-3333-3333-3333-333333333301',
    'Nguyễn Diệu Linh',
    'ACTIVE',
    'Beauty reviewer nổi tiếng trên TikTok với tỷ lệ tương tác cao',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    '33333333-3333-3333-3333-333333333312',
    '33333333-3333-3333-3333-333333333302',
    'Trần Anh Tuấn',
    'ACTIVE',
    'Kênh review đồ công nghệ và phong cách sống hiện đại',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (user_id) DO NOTHING;

-- ----------------------------------------------------------------------------------------------------
-- 6. FLOW 2: DỮ LIỆU KOC KHÁM PHÁ & CHỈ SỐ MẠNG XÃ HỘI (CREATORS & PUBLIC METRICS)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO creator (
    id, platform, external_id, user_name, display_name, location,
    profile_url, avatar_url, master_id, source_type, is_stable_id, authorized_at, created_at, updated_at
) VALUES
-- 1. TikTok @glow.beauty (Authorized KOC)
(
    '55555555-5555-5555-5555-555555555501',
    'TIKTOK',
    'tiktok_glow_beauty_123',
    '@glow.beauty',
    'Glow Beauty Review',
    'Ho Chi Minh City',
    'https://www.tiktok.com/@glow.beauty',
    'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=400',
    '33333333-3333-3333-3333-333333333311',
    'AUTHORIZED',
    TRUE,
    CURRENT_TIMESTAMP - INTERVAL '30 days',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
-- 2. YouTube Tech Lab VN (Authorized KOC)
(
    '55555555-5555-5555-5555-555555555502',
    'YOUTUBE',
    'UC_techlab_vn_999',
    '@techlab.vn',
    'Tech Lab Vietnam',
    'Hanoi',
    'https://www.youtube.com/@techlab.vn',
    'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=400',
    '33333333-3333-3333-3333-333333333312',
    'AUTHORIZED',
    TRUE,
    CURRENT_TIMESTAMP - INTERVAL '20 days',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
-- 3. TikTok @skincare.minh (Public KOC)
(
    '55555555-5555-5555-5555-555555555503',
    'TIKTOK',
    'tiktok_skincare_minh_456',
    '@skincare.minh',
    'Minh Skincare Science',
    'Danang',
    'https://www.tiktok.com/@skincare.minh',
    'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=400',
    NULL,
    'PUBLIC',
    TRUE,
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
-- 4. Instagram @fashion.lan (Public KOC)
(
    '55555555-5555-5555-5555-555555555504',
    'INSTAGRAM',
    'ig_fashion_lan_789',
    '@fashion.lan',
    'Lan Le OOTD',
    'Ho Chi Minh City',
    'https://www.instagram.com/fashion.lan',
    'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=400',
    NULL,
    'PUBLIC',
    TRUE,
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (platform, external_id) DO NOTHING;

-- Public Metrics
INSERT INTO public_creator_metric (
    id, creator_id, followers, avg_views, avg_likes, avg_comments, avg_shares,
    engagement_rate, niche, location, content_summary, collected_at,
    freshness_status, data_confidence, category, creator_type
) VALUES
(
    '55555555-5555-5555-5555-555555555511',
    '55555555-5555-5555-5555-555555555501',
    350000,
    45000.00,
    2800.00,
    180.00,
    60.00,
    0.0480,
    'BEAUTY',
    'Ho Chi Minh City',
    'Chuyên review mỹ phẩm chăm sóc da mặt, kem chống nắng, son môi, swatch bảng màu make up.',
    CURRENT_TIMESTAMP,
    'FRESH',
    0.9500,
    'BEAUTY',
    'KOC'
),
(
    '55555555-5555-5555-5555-555555555512',
    '55555555-5555-5555-5555-555555555502',
    185000,
    32000.00,
    1500.00,
    120.00,
    40.00,
    0.0350,
    'LIFESTYLE',
    'Hanoi',
    'Đánh giá đồ công nghệ hằng ngày, thiết bị chăm sóc cá nhân thông minh, lifestyle người trẻ.',
    CURRENT_TIMESTAMP,
    'FRESH',
    0.9000,
    'TECH',
    'KOC'
),
(
    '55555555-5555-5555-5555-555555555513',
    '55555555-5555-5555-5555-555555555503',
    120000,
    22000.00,
    1600.00,
    90.00,
    25.00,
    0.0520,
    'SKINCARE',
    'Danang',
    'Phân tích thành phần dược mỹ phẩm khoa học, trị mụn, bảo vệ hàng rào da.',
    CURRENT_TIMESTAMP,
    'FRESH',
    0.8800,
    'BEAUTY',
    'KOC'
),
(
    '55555555-5555-5555-5555-555555555514',
    '55555555-5555-5555-5555-555555555504',
    95000,
    15000.00,
    1200.00,
    65.00,
    20.00,
    0.0420,
    'FASHION',
    'Ho Chi Minh City',
    'Phối đồ đi làm thanh lịch, phong cách mùa hè, phụ kiện và trang điểm nhẹ nhàng.',
    CURRENT_TIMESTAMP,
    'FRESH',
    0.8500,
    'LIFESTYLE',
    'KOC'
)
ON CONFLICT (id) DO NOTHING;

-- ----------------------------------------------------------------------------------------------------
-- 7. FLOW 3: LƯỢT ĐỀ XUẤT AI GẦN NHẤT (RECOMMENDATION RUN)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO recommendation_run (
    id, campaign_id, brand_profile_id, context_version, status, candidates_count, query_brief, created_at
) VALUES (
    '66666666-6666-6666-6666-666666666601',
    '22222222-2222-2222-2222-222222222241',
    '22222222-2222-2222-2222-222222222211',
    1,
    'COMPLETED',
    4,
    'Tìm kiếm KOC làm đẹp và phong cách sống để quảng bá kem chống nắng tone-up mùa hè 2026',
    CURRENT_TIMESTAMP - INTERVAL '1 days'
)
ON CONFLICT (id) DO NOTHING;

-- ----------------------------------------------------------------------------------------------------
-- 8. FLOW 4: GIỎ LƯU KOC (SHORTLIST ITEMS) & QUẢN TRỊ CRM (RELATIONSHIPS)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO shortlist_item (
    id, campaign_id, creator_id, user_id, master_id, priority, status, source, recommendation_run_id, note, added_at
) VALUES
(
    '77777777-7777-7777-7777-777777777701',
    '22222222-2222-2222-2222-222222222241',
    '55555555-5555-5555-5555-555555555501',
    '22222222-2222-2222-2222-222222222201',
    '33333333-3333-3333-3333-333333333311',
    'HIGH',
    'OUTREACHED',
    'AI_RECOMMENDED',
    '66666666-6666-6666-6666-666666666601',
    'KOC tiềm năng số 1 cho chiến dịch chống nắng',
    CURRENT_TIMESTAMP - INTERVAL '7 days'
),
(
    '77777777-7777-7777-7777-777777777702',
    '22222222-2222-2222-2222-222222222241',
    '55555555-5555-5555-5555-555555555503',
    '22222222-2222-2222-2222-222222222201',
    NULL,
    'MEDIUM',
    'SAVED',
    'MANUAL_DISCOVERY',
    NULL,
    'Chuyên gia skincare khoa học, phù hợp giải thích thành phần',
    CURRENT_TIMESTAMP - INTERVAL '5 days'
)
ON CONFLICT (campaign_id, creator_id) DO NOTHING;

-- Phễu CRM Relationships ở các giai đoạn
INSERT INTO relationship (
    id, user_id, creator_id, master_id, campaign_id, shortlist_item_id,
    stage, contact_method, quoted_fee, agreed_fee, currency,
    last_contact_at, notes, next_action, next_action_due_at, created_at, updated_at
) VALUES
-- Quan hệ 1: Stage COLLABORATING (Sẵn sàng tạo Collaboration ở Flow 5)
(
    '88888888-8888-8888-8888-888888888801',
    '22222222-2222-2222-2222-222222222201',
    '55555555-5555-5555-5555-555555555501',
    '33333333-3333-3333-3333-333333333311',
    '22222222-2222-2222-2222-222222222241',
    '77777777-7777-7777-7777-777777777701',
    'COLLABORATING',
    'EMAIL_DIRECT',
    4000000.00,
    3500000.00,
    'VND',
    CURRENT_TIMESTAMP - INTERVAL '3 days',
    'KOC đồng ý nhận thù lao 3.500.000 VND cho 1 video TikTok 60s độc quyền',
    'Chờ Brand bấm tạo hợp tác chính thức trên hệ thống',
    CURRENT_TIMESTAMP + INTERVAL '3 days',
    CURRENT_TIMESTAMP - INTERVAL '6 days',
    CURRENT_TIMESTAMP
),
-- Quan hệ 2: Stage CONTACTED
(
    '88888888-8888-8888-8888-888888888802',
    '22222222-2222-2222-2222-222222222201',
    '55555555-5555-5555-5555-555555555502',
    '33333333-3333-3333-3333-333333333312',
    '22222222-2222-2222-2222-222222222241',
    NULL,
    'CONTACTED',
    'SYSTEM_OUTREACH',
    5000000.00,
    NULL,
    'VND',
    CURRENT_TIMESTAMP - INTERVAL '2 days',
    'Đã gửi brief chiến dịch qua hệ thống, đề xuất KOC làm video tích hợp 3 phút trên YouTube',
    'Đang chờ KOC phản hồi lời mời hợp tác',
    CURRENT_TIMESTAMP + INTERVAL '5 days',
    CURRENT_TIMESTAMP - INTERVAL '2 days',
    CURRENT_TIMESTAMP
),
-- Quan hệ 3: Stage COMPLETED (Chiến dịch trước đó đã hoàn tất)
(
    '88888888-8888-8888-8888-888888888803',
    '22222222-2222-2222-2222-222222222201',
    '55555555-5555-5555-5555-555555555501',
    '33333333-3333-3333-3333-333333333311',
    '22222222-2222-2222-2222-222222222241',
    NULL,
    'COMPLETED',
    'EMAIL_DIRECT',
    3000000.00,
    3000000.00,
    'VND',
    CURRENT_TIMESTAMP - INTERVAL '15 days',
    'Chiến dịch video test thử nghiệm đã nghiệm thu thành công',
    'Đã lưu kết quả vào Bộ nhớ AI',
    NULL,
    CURRENT_TIMESTAMP - INTERVAL '20 days',
    CURRENT_TIMESTAMP - INTERVAL '10 days'
)
ON CONFLICT (id) DO NOTHING;

-- ----------------------------------------------------------------------------------------------------
-- 9. FLOW 5: HỢP TÁC, BẰNG CHỨNG & NGHIỆM THU (COLLABORATION, EVIDENCE & OUTCOME)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO collaboration (
    id, relationship_id, campaign_id, creator_id, master_id,
    agreed_fee, currency, status, published_content_url,
    start_at, completed_at, note, payment_status, updated_at
) VALUES
-- Hợp tác 1: Đang thực thi IN_PROGRESS (Sẵn sàng test nộp evidence, outcome, review)
(
    '99999999-9999-9999-9999-999999999901',
    '88888888-8888-8888-8888-888888888801',
    '22222222-2222-2222-2222-222222222241',
    '55555555-5555-5555-5555-555555555501',
    '33333333-3333-3333-3333-333333333311',
    3500000.00,
    'VND',
    'IN_PROGRESS',
    'https://www.tiktok.com/@glow.beauty/video/7345678901234567890',
    CURRENT_TIMESTAMP - INTERVAL '4 days',
    NULL,
    'KOC cam kết đăng bài vào khung giờ vàng 20h00 tối thứ 6 kèm hashtag #AcmeToneUp #GlowSkin',
    'PAID',
    CURRENT_TIMESTAMP
),
-- Hợp tác 2: Đã COMPLETED (Có sẵn Outcome, Review, M1 & M4)
(
    '99999999-9999-9999-9999-999999999902',
    '88888888-8888-8888-8888-888888888803',
    '22222222-2222-2222-2222-222222222241',
    '55555555-5555-5555-5555-555555555501',
    '33333333-3333-3333-3333-333333333311',
    3000000.00,
    'VND',
    'COMPLETED',
    'https://www.tiktok.com/@glow.beauty/video/7311111111111111111',
    CURRENT_TIMESTAMP - INTERVAL '20 days',
    CURRENT_TIMESTAMP - INTERVAL '10 days',
    'Hợp tác thử nghiệm giai đoạn 1 hoàn thành xuất sắc',
    'PAID',
    CURRENT_TIMESTAMP - INTERVAL '10 days'
)
ON CONFLICT (id) DO NOTHING;

-- Bằng chứng đã nộp cho Hợp tác 1
INSERT INTO evidence_asset (
    id, collaboration_id, outcome_id, asset_type, storage_url, source, uploaded_at
) VALUES
(
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa01',
    '99999999-9999-9999-9999-999999999901',
    NULL,
    'POST_LINK',
    'https://www.tiktok.com/@glow.beauty/video/7345678901234567890',
    'CREATOR_SUBMITTED',
    CURRENT_TIMESTAMP - INTERVAL '2 days'
),
(
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa02',
    '99999999-9999-9999-9999-999999999901',
    NULL,
    'INSIGHTS_SCREENSHOT',
    'https://cdn.influencermatch.com/evidence/insights_glow_beauty_2026.png',
    'CREATOR_SUBMITTED',
    CURRENT_TIMESTAMP - INTERVAL '1 days'
)
ON CONFLICT (id) DO NOTHING;

-- Kết quả nghiệm thu tương tác cho Hợp tác 2
INSERT INTO campaign_outcome (
    id, collaboration_id, views, clicks, likes, comments, shares,
    evidence_quality, result_note, recorded_at, completed_at
) VALUES (
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb01',
    '99999999-9999-9999-9999-999999999902',
    38500,
    620,
    2800,
    150,
    45,
    'SELF_REPORTED',
    'Video lên xu hướng TikTok For You Page, tỷ lệ giữ chân người xem đạt 68%',
    CURRENT_TIMESTAMP - INTERVAL '10 days',
    CURRENT_TIMESTAMP - INTERVAL '10 days'
)
ON CONFLICT (collaboration_id) DO NOTHING;

-- Đánh giá Creator Review cho Hợp tác 2
INSERT INTO creator_review (
    id, outcome_id, collaboration_id, reviewed_by_user_id,
    overall_rating, brand_fit_rating, would_collaborate_again, note, reviewed_at
) VALUES (
    'cccccccc-cccc-cccc-cccc-cccccccccc01',
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb01',
    '99999999-9999-9999-9999-999999999902',
    '22222222-2222-2222-2222-222222222201',
    5,
    5,
    TRUE,
    'KOC làm việc vô cùng chuyên nghiệp, đúng hẹn, chất lượng quay dựng video sắc nét và hiểu rất sâu về thành phần sản phẩm.',
    CURRENT_TIMESTAMP - INTERVAL '9 days'
)
ON CONFLICT (collaboration_id) DO NOTHING;

-- ----------------------------------------------------------------------------------------------------
-- 10. FLOW 6: BỘ NHỚ AI HỌC HỎI SAU CHIẾN DỊCH (M1 HISTORICAL EVIDENCE & M4 BRAND PATTERN)
-- ----------------------------------------------------------------------------------------------------
INSERT INTO historical_campaign (
    id, creator_id, master_id, collaboration_id, outcome_id,
    evidence_type, quality, evidence_value, recorded_at
) VALUES (
    'dddddddd-dddd-dddd-dddd-dddddddddd01',
    '55555555-5555-5555-5555-555555555501',
    '33333333-3333-3333-3333-333333333311',
    '99999999-9999-9999-9999-999999999902',
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb01',
    'COLLABORATION_REVIEW',
    'VERIFIED',
    '{"views": 38500, "clicks": 620, "likes": 2800, "shares": 45, "comments": 150, "overallRating": 5, "brandFitRating": 5, "reliabilityScore": 1.0, "publishedContentUrl": "https://www.tiktok.com/@glow.beauty/video/7311111111111111111"}'::jsonb,
    CURRENT_TIMESTAMP - INTERVAL '9 days'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO brand_pattern (
    id, brand_profile_id, platform, niche, pattern_type,
    pattern_value, confidence, evidence_count, average_successful_score, learned_at
) VALUES (
    'eeeeeeee-eeee-eeee-eeee-eeeeeeeeee01',
    '22222222-2222-2222-2222-222222222211',
    'TIKTOK',
    'BEAUTY',
    'COLLABORATION_SUCCESS',
    '{"niche": "BEAUTY", "platform": "TIKTOK", "avgViews": 38500, "avgLikes": 2800, "successCount": 1, "topPerformingTopics": ["SUNSCREEN", "TONE_UP"]}'::jsonb,
    0.5500,
    1,
    5.0000,
    CURRENT_TIMESTAMP - INTERVAL '9 days'
)
ON CONFLICT (id) DO NOTHING;

COMMIT;
