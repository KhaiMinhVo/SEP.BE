CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- Insert sample Admin account (Password: Password123)
INSERT INTO users (id, email, password, full_name, role, status, created_at, updated_at)
VALUES (
    '550e8400-e29b-41d4-a716-446655440000',
    'admin@influencermatch.com',
    '$2a$10$wY.O2g.B1761d1R.fG/j.O7NlXbY51FvA0/Z8B/UqQ9D0n4C.2K8a',
    'System Admin',
    'ADMIN',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- Insert sample Brand account (Password: Password123)
INSERT INTO users (id, email, password, full_name, role, status, created_at, updated_at)
VALUES (
    '550e8400-e29b-41d4-a716-446655440001',
    'brand@influencermatch.com',
    '$2a$10$wY.O2g.B1761d1R.fG/j.O7NlXbY51FvA0/Z8B/UqQ9D0n4C.2K8a',
    'Apple Vietnam',
    'BRAND',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);
