ALTER TABLE "user" ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE "user" ADD CONSTRAINT ck_user_role CHECK (role IN ('BRAND', 'DATA_MANAGER', 'ADMIN'));
CREATE TABLE rbac_guard (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO rbac_guard(id) VALUES (1);
CREATE INDEX ix_audit_log_created_at ON audit_log(created_at DESC);
CREATE INDEX ix_audit_log_user_id ON audit_log(user_id);
CREATE INDEX ix_audit_log_entity ON audit_log(entity_type, entity_id);
