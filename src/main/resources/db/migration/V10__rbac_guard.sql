-- V10__rbac_guard.sql
CREATE TABLE IF NOT EXISTS rbac_guard (
    id INTEGER PRIMARY KEY CHECK (id = 1)
);

INSERT INTO rbac_guard(id) VALUES (1) ON CONFLICT DO NOTHING;
