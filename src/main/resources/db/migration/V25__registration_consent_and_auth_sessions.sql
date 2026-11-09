ALTER TABLE brand_profile ADD COLUMN phone_number VARCHAR(20);
ALTER TABLE brand_profile ADD COLUMN onboarding_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE brand_profile ADD COLUMN onboarding_completed_at TIMESTAMPTZ;
ALTER TABLE brand_profile ADD CONSTRAINT ck_brand_onboarding CHECK (onboarding_status IN ('PENDING','COMPLETED'));
CREATE TABLE user_policy_acceptance (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES "user"(id), policy_type VARCHAR(20) NOT NULL,
 policy_version VARCHAR(100) NOT NULL, accepted_at TIMESTAMPTZ NOT NULL, source VARCHAR(40) NOT NULL,
 UNIQUE(user_id,policy_type,policy_version));
CREATE TABLE auth_session (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES "user"(id), auth_version BIGINT NOT NULL,
 login_method VARCHAR(30) NOT NULL, created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ, CHECK(expires_at>created_at));
CREATE INDEX idx_auth_session_user ON auth_session(user_id);
ALTER TABLE refresh_token ADD COLUMN session_id UUID REFERENCES auth_session(id);
-- Historical timestamps represent UTC. Use absolute instants for new session-bound tokens.
ALTER TABLE refresh_token ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
ALTER TABLE refresh_token ALTER COLUMN expires_at TYPE TIMESTAMPTZ USING expires_at AT TIME ZONE 'UTC';
ALTER TABLE refresh_token ALTER COLUMN revoked_at TYPE TIMESTAMPTZ USING revoked_at AT TIME ZONE 'UTC';
CREATE INDEX idx_refresh_session ON refresh_token(session_id);
UPDATE refresh_token SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP);
UPDATE "user" SET auth_version=auth_version+1;
ALTER TABLE google_login_session ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'LOGIN';
ALTER TABLE google_login_session ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'NEW';
ALTER TABLE google_login_session ADD COLUMN user_id UUID REFERENCES "user"(id);
ALTER TABLE google_login_session ADD COLUMN auth_session_id UUID REFERENCES auth_session(id);
ALTER TABLE google_login_session ADD COLUMN auth_version BIGINT;
ALTER TABLE google_login_session ADD COLUMN google_subject VARCHAR(255);
ALTER TABLE google_login_session ADD COLUMN google_email VARCHAR(255);
ALTER TABLE google_login_session ADD COLUMN google_name VARCHAR(150);
UPDATE google_login_session SET expires_at=LEAST(expires_at,CURRENT_TIMESTAMP);
ALTER TABLE meta_oauth_session ADD COLUMN auth_session_id UUID REFERENCES auth_session(id);
ALTER TABLE tiktok_oauth_session ADD COLUMN auth_session_id UUID REFERENCES auth_session(id);
