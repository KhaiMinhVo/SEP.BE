-- ====================================================================================================
-- DEMO SEED: CREATOR NGUYENVY19042005@GMAIL.COM + 3 KÊNH (FACEBOOK, TIKTOK, INSTAGRAM)
-- ====================================================================================================
-- Mật khẩu đăng nhập mẫu: Password123!
-- BCrypt Hash: $2a$10$aho.VNRalTCL7ZLKHwezT.Rk/4a91pTybHNw1bJC1r9KKX6cSZnCa
--
-- HƯỚNG DẪN ĐIỀN PLACEHOLDER TRƯỚC KHI CHẠY:
-- 1. Facebook: Thay <FB_ID_TU_LINK> bằng mã ID số trong link Facebook / Page ID (ví dụ: 61551234567890).
-- 2. TikTok:   Thay <TIKTOK_USERNAME> bằng tên kênh TikTok (không có @ ở URL, có @ ở user_name).
--              Thay <TIKTOK_NUMERIC_USER_ID> bằng author_id dạng số [1-9][0-9]{0,19} (bắt buộc nếu test TikTok OAuth).
-- 3. Instagram: Thay <IG_USERNAME> bằng tên kênh Instagram.
--              Thay <IG_NUMERIC_ACCOUNT_ID> bằng IG Professional Account ID dạng số (bắt buộc nếu test IG OAuth).
-- ====================================================================================================

BEGIN;

-- 1. Tạo tài khoản Creator với email thật của bạn để nhận SMTP OTP / Invitation / Notification
INSERT INTO "user" (
    id, email, password_hash, full_name, role, status, email_verified, created_at, updated_at
) VALUES (
    '33333333-3333-3333-3333-333333333399',
    'nguyenvy19042005@gmail.com',
    '$2a$10$aho.VNRalTCL7ZLKHwezT.Rk/4a91pTybHNw1bJC1r9KKX6cSZnCa',
    'Nguyen Vy (Creator Demo)',
    'CREATOR',
    'ACTIVE',
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (email) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    email_verified = TRUE,
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

-- 2. Tạo 3 kênh PUBLIC chưa Claim (master_id = NULL) để Brand tìm thấy ở WF1, mời ở WF2, rồi bạn tự Claim ở WF4
INSERT INTO creator (
    id, platform, external_id, user_name, display_name, location,
    profile_url, avatar_url, master_id, source_type, is_stable_id, authorized_at, created_at, updated_at
) VALUES
-- Kênh 1: Facebook (dùng mã ID trong link)
(
    '55555555-5555-5555-5555-555555555591',
    'FACEBOOK',
    '<FB_ID_TU_LINK>',
    '<FB_ID_TU_LINK>',
    'Nguyen Vy Facebook Demo',
    'Ho Chi Minh City',
    'https://www.facebook.com/<FB_ID_TU_LINK>',
    'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=400',
    NULL,
    'PUBLIC',
    TRUE,
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
-- Kênh 2: TikTok (dùng tên kênh + numeric author_id để pass TikTokIdentityResolver)
(
    '55555555-5555-5555-5555-555555555592',
    'TIKTOK',
    '<TIKTOK_NUMERIC_USER_ID>',
    '@<TIKTOK_USERNAME>',
    'Nguyen Vy TikTok Demo',
    'Ho Chi Minh City',
    'https://www.tiktok.com/@<TIKTOK_USERNAME>',
    'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=400',
    NULL,
    'PUBLIC',
    TRUE,
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
-- Kênh 3: Instagram (dùng tên kênh + numeric IG Account ID để pass MetaOwnershipService)
(
    '55555555-5555-5555-5555-555555555593',
    'INSTAGRAM',
    '<IG_NUMERIC_ACCOUNT_ID>',
    '@<IG_USERNAME>',
    'Nguyen Vy Instagram Demo',
    'Ho Chi Minh City',
    'https://www.instagram.com/<IG_USERNAME>',
    'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=400',
    NULL,
    'PUBLIC',
    TRUE,
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (platform, external_id) DO NOTHING;

-- 3. Public Metrics FRESH để cả 3 kênh đủ điều kiện hiển thị ở WF1 Discovery & Shortlist
INSERT INTO public_creator_metric (
    id, creator_id, followers, avg_views, avg_likes, avg_comments, avg_shares,
    engagement_rate, niche, location, content_summary, collected_at,
    freshness_status, data_confidence, category, creator_type
) VALUES
(
    '55555555-5555-5555-5555-555555555594',
    '55555555-5555-5555-5555-555555555591',
    85000,
    18000.00,
    950.00,
    80.00,
    30.00,
    0.0450,
    'BEAUTY',
    'Ho Chi Minh City',
    'Kênh Facebook chia sẻ review mỹ phẩm, skincare và phong cách sống hằng ngày.',
    CURRENT_TIMESTAMP,
    'FRESH',
    0.9200,
    'BEAUTY',
    'KOC'
),
(
    '55555555-5555-5555-5555-555555555595',
    '55555555-5555-5555-5555-555555555592',
    120000,
    28000.00,
    1800.00,
    120.00,
    45.00,
    0.0510,
    'BEAUTY',
    'Ho Chi Minh City',
    'Kênh TikTok review kem chống nắng, chăm sóc da và lifestyle giới trẻ.',
    CURRENT_TIMESTAMP,
    'FRESH',
    0.9400,
    'BEAUTY',
    'KOC'
),
(
    '55555555-5555-5555-5555-555555555596',
    '55555555-5555-5555-5555-555555555593',
    95000,
    16000.00,
    1300.00,
    75.00,
    25.00,
    0.0440,
    'SKINCARE',
    'Ho Chi Minh City',
    'Kênh Instagram Reels chia sẻ routine dưỡng da, thời trang và làm đẹp.',
    CURRENT_TIMESTAMP,
    'FRESH',
    0.9100,
    'BEAUTY',
    'KOC'
)
ON CONFLICT (id) DO NOTHING;

COMMIT;
