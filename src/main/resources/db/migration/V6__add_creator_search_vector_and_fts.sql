-- V5__add_creator_search_vector_and_fts.sql
-- 1. Kích hoạt extension pg_trgm để hỗ trợ tìm kiếm mờ (Fuzzy Search / Typo Tolerance)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2. Tạo cột ảo (Generated Column) search_vector gom 3 trường text lại với nhau
-- Sử dụng từ điển 'simple' để bỏ qua các quy tắc chia động từ phức tạp, tập trung vào đối sánh ký tự.
ALTER TABLE public_creator_metric 
ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    to_tsvector('simple', coalesce(content_summary, '') || ' ' || 
                          coalesce(niche, '') || ' ' || 
                          coalesce(category, ''))
) STORED;

-- 3. Tạo GIN Index siêu tốc độ bám vào cột ảo vừa tạo
CREATE INDEX idx_creator_search ON public_creator_metric USING GIN(search_vector);
