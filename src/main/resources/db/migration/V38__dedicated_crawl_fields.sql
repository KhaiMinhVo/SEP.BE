-- Crawl configuration is independent of observed creator/AI niche labels.
-- Seeds copied from CategoryDiscoveryService.get_supported_categories in the crawler.
CREATE TABLE crawl_field (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL CHECK (name = BTRIM(name) AND name <> ''),
    description VARCHAR(500) NOT NULL DEFAULT '',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    keywords JSONB NOT NULL DEFAULT '[]'::jsonb
);
CREATE UNIQUE INDEX uq_crawl_field_name ON crawl_field (LOWER(name));

INSERT INTO crawl_field (name, keywords) VALUES
    ('Làm đẹp & Mỹ phẩm', '["review mỹ phẩm", "skincare review", "makeup tutorial", "son môi hot"]'::jsonb),
    ('Công nghệ & Điện thoại / PC', '["review công nghệ", "đập hộp điện thoại", "setup bàn làm việc", "đồ chơi công nghệ"]'::jsonb),
    ('Ẩm thực & Nhà hàng', '["review đồ ăn", "món ngon đường phố", "quán ăn hà nội", "food tour sài gòn"]'::jsonb),
    ('Thời trang & Phụ kiện', '["review thời trang", "haul quần áo shopee", "phối đồ đẹp", "ootd vietnam"]'::jsonb),
    ('Truyền thông & Giải trí', '["hài hước", "giải trí", "review phim"]'::jsonb),
    ('Du lịch & Trải nghiệm', '["review du lịch", "check-in đà lạt", "khám phá việt nam", "kinh nghiệm du lịch"]'::jsonb),
    ('Đời sống, Sức khỏe & Gia đình', '["review gia dụng", "mẹo vặt cuộc sống", "decor phòng ngủ", "daily vlog"]'::jsonb);
