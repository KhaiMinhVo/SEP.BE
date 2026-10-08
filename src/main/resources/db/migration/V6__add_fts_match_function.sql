-- V6__add_fts_match_function.sql
-- Tao function de ho tro Hibernate Criteria API goi custom operator (@@)
CREATE OR REPLACE FUNCTION fts_match(vector tsvector, query text) 
RETURNS boolean AS $$
BEGIN
    RETURN vector @@ to_tsquery('simple', query);
END;
$$ LANGUAGE plpgsql IMMUTABLE;
