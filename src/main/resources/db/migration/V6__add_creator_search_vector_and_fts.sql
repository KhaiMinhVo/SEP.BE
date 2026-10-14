-- V6__add_creator_search_vector_and_fts.sql
-- 1. Kich hoat extension pg_trgm ho tro tim kiem mo (Fuzzy Search / Typo Tolerance)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2. Tao cot ao (Generated Column) search_vector gom 3 truong text lai voi nhau
ALTER TABLE public_creator_metric 
ADD COLUMN IF NOT EXISTS search_vector tsvector GENERATED ALWAYS AS (
    to_tsvector('simple', coalesce(content_summary, '') || ' ' || 
                          coalesce(niche, '') || ' ' || 
                          coalesce(category, ''))
) STORED;

-- 3. Tao GIN Index tren search_vector
CREATE INDEX IF NOT EXISTS idx_creator_search ON public_creator_metric USING GIN(search_vector);