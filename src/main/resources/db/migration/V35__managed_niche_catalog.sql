CREATE TABLE niche_catalog (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL CHECK (name = BTRIM(name) AND name <> ''),
    description VARCHAR(500) NOT NULL DEFAULT '',
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE UNIQUE INDEX uq_niche_catalog_name ON niche_catalog (LOWER(name));

-- Bootstrap the catalogue once. Managers maintain it through the API afterwards.
INSERT INTO niche_catalog (name) VALUES
    ('Làm đẹp & Mỹ phẩm'),
    ('Chăm sóc da & Skincare'),
    ('Thời trang & Phụ kiện'),
    ('Công nghệ & Điện thoại / PC'),
    ('Ẩm thực & Nhà hàng'),
    ('Sức khỏe & Thể hình'),
    ('Du lịch & Trải nghiệm'),
    ('Mẹ & Bé'),
    ('Đời sống, Sức khỏe & Gia đình'),
    ('Truyền thông & Giải trí'),
    ('Tài chính & Đầu tư'),
    ('Sản phẩm tiêu dùng');

-- Keep existing selectable niches when migrating away from DISTINCT metric values.
INSERT INTO niche_catalog (name)
SELECT MIN(BTRIM(niche))
FROM public_creator_metric
WHERE niche IS NOT NULL AND BTRIM(niche) <> ''
  AND LENGTH(BTRIM(niche)) <= 100
  AND UPPER(BTRIM(niche)) NOT IN ('UNKNOWN', 'GENERAL')
GROUP BY LOWER(BTRIM(niche))
ON CONFLICT DO NOTHING;
