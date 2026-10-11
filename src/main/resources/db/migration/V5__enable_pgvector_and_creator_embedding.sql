CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE creator ADD COLUMN IF NOT EXISTS embedding vector(768);

CREATE INDEX IF NOT EXISTS idx_creator_embedding_hnsw 
ON creator USING hnsw (embedding vector_cosine_ops);
