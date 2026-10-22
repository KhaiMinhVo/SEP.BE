-- V4__creator_hybrid_search_vector.sql
-- 1. Kích hoạt extension pg_trgm để hỗ trợ tìm kiếm mờ (Fuzzy Search / Typo Tolerance)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2. Tạo cột ảo (Generated Column) search_vector gom 3 trường text lại với nhau
ALTER TABLE public_creator_metric 
ADD COLUMN IF NOT EXISTS search_vector tsvector GENERATED ALWAYS AS (
    to_tsvector('simple', coalesce(content_summary, '') || ' ' || 
                          coalesce(niche, '') || ' ' || 
                          coalesce(category, ''))
) STORED;

-- 3. Tạo GIN Index cho full-text search
CREATE INDEX IF NOT EXISTS idx_creator_search ON public_creator_metric USING GIN(search_vector);

-- 4. Hàm so khớp fts_match
CREATE OR REPLACE FUNCTION fts_match(tsv tsvector, query_str text)
RETURNS boolean AS $$
BEGIN
    RETURN tsv @@ to_tsquery('simple', query_str);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- 5. Kích hoạt extension pgvector và bổ sung embedding cho bảng creator
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE creator ADD COLUMN IF NOT EXISTS embedding vector(768);

CREATE INDEX IF NOT EXISTS idx_creator_embedding_hnsw 
ON creator USING hnsw (embedding vector_cosine_ops);
