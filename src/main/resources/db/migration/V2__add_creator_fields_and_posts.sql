-- Add display_name and avatar_url to creator table
ALTER TABLE creator ADD COLUMN IF NOT EXISTS display_name VARCHAR(255);
ALTER TABLE creator ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(1000);

-- Add creator_type to public_creator_metric table
ALTER TABLE public_creator_metric ADD COLUMN IF NOT EXISTS creator_type VARCHAR(50);

-- Create post table for storing recent videos
CREATE TABLE IF NOT EXISTS post (
    id UUID PRIMARY KEY,
    creator_id UUID NOT NULL,
    platform_post_id VARCHAR(255) NOT NULL,
    post_url VARCHAR(1000),
    caption TEXT,
    views BIGINT DEFAULT 0,
    likes BIGINT DEFAULT 0,
    comments BIGINT DEFAULT 0,
    shares BIGINT DEFAULT 0,
    posted_at VARCHAR(100),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW(),
    CONSTRAINT fk_post_creator FOREIGN KEY (creator_id) REFERENCES creator (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_post_creator_id ON post(creator_id);
