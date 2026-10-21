-- V6__role_permission_overrides.sql
CREATE TABLE IF NOT EXISTS role_permission_config (
    id UUID PRIMARY KEY,
    role VARCHAR(32) NOT NULL UNIQUE CHECK (role IN ('BRAND', 'CREATOR', 'DATA_MANAGER')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

INSERT INTO role_permission_config (id, role) VALUES
 ('00000000-0000-0000-0000-000000000101', 'BRAND'),
 ('00000000-0000-0000-0000-000000000102', 'CREATOR'),
 ('00000000-0000-0000-0000-000000000103', 'DATA_MANAGER')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS role_permission_override (
    id UUID PRIMARY KEY,
    role VARCHAR(32) NOT NULL REFERENCES role_permission_config(role),
    permission VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL,
    CONSTRAINT uq_role_permission UNIQUE (role, permission)
);
