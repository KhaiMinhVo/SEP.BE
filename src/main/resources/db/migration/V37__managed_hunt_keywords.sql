ALTER TABLE niche_catalog ADD COLUMN keywords JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE niche_catalog SET keywords = '["review mỹ phẩm", "skincare review", "makeup tutorial", "son môi hot"]'::jsonb WHERE name = 'Làm đẹp & Mỹ phẩm' AND keywords = '[]'::jsonb;

UPDATE niche_catalog SET keywords = '["skincare routine", "review chăm sóc da", "kem chống nắng"]'::jsonb WHERE name = 'Chăm sóc da & Skincare' AND keywords = '[]'::jsonb;

UPDATE niche_catalog SET keywords = '["review thời trang", "haul quần áo shopee", "phối đồ đẹp", "ootd vietnam"]'::jsonb WHERE name = 'Thời trang & Phụ kiện' AND keywords = '[]'::jsonb;

UPDATE niche_catalog SET keywords = '["review công nghệ", "đập hộp điện thoại", "setup bàn làm việc", "đồ chơi công nghệ"]'::jsonb WHERE name = 'Công nghệ & Điện thoại / PC' AND keywords = '[]'::jsonb;

UPDATE niche_catalog SET keywords = '["review đồ ăn", "món ngon đường phố", "quán ăn hà nội", "food tour sài gòn"]'::jsonb WHERE name = 'Ẩm thực & Nhà hàng' AND keywords = '[]'::jsonb;

UPDATE niche_catalog SET keywords = '["tập gym", "fitness vietnam", "sống khỏe"]'::jsonb WHERE name = 'Sức khỏe & Thể hình' AND keywords = '[]'::jsonb;

UPDATE niche_catalog SET keywords = '["review du lịch", "check-in đà lạt", "khám phá việt nam", "kinh nghiệm du lịch"]'::jsonb WHERE name = 'Du lịch & Trải nghiệm' AND keywords = '[]'::jsonb;

UPDATE niche_catalog SET keywords = '["review mẹ và bé", "chăm sóc trẻ", "nuôi con"]'::jsonb WHERE name = 'Mẹ & Bé' AND keywords = '[]'::jsonb;

UPDATE niche_catalog SET keywords = '["review gia dụng", "mẹo vặt cuộc sống", "decor phòng ngủ", "daily vlog"]'::jsonb WHERE name = 'Đời sống, Sức khỏe & Gia đình' AND keywords = '[]'::jsonb;

UPDATE niche_catalog SET keywords = '["hài hước", "giải trí", "review phim"]'::jsonb WHERE name = 'Truyền thông & Giải trí' AND keywords = '[]'::jsonb;

UPDATE niche_catalog SET keywords = '["tài chính cá nhân", "kiến thức đầu tư", "quản lý chi tiêu"]'::jsonb WHERE name = 'Tài chính & Đầu tư' AND keywords = '[]'::jsonb;

UPDATE niche_catalog SET keywords = '["review sản phẩm", "đồ dùng tiện ích", "mua sắm thông minh"]'::jsonb WHERE name = 'Sản phẩm tiêu dùng' AND keywords = '[]'::jsonb;
