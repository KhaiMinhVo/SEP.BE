-- Username matching is an MVP policy, not a conversion from OAuth open_id to crawler ID.
CREATE FUNCTION im_tiktok_username(value text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT lower(regexp_replace(btrim(value, E'\001\002\003\004\005\006\007\010\011\012\013\014\015\016\017\020\021\022\023\024\025\026\027\030\031\032\033\034\035\036\037\040'), '^@', '')) $$;
CREATE INDEX ix_creator_tiktok_username ON creator(im_tiktok_username(user_name)) WHERE platform='TIKTOK';

CREATE TABLE tiktok_account_binding (
 id UUID PRIMARY KEY,
 environment VARCHAR(16) NOT NULL CHECK(environment='SANDBOX'),
 client_key VARCHAR(255) NOT NULL,
 open_id VARCHAR(255) NOT NULL,
 creator_id UUID NOT NULL REFERENCES creator(id),
 username VARCHAR(255) NOT NULL,
 verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_tiktok_binding_subject UNIQUE(environment,client_key,open_id),
 CONSTRAINT uq_tiktok_binding_creator UNIQUE(environment,client_key,creator_id)
);
ALTER TABLE tiktok_oauth_session DROP CONSTRAINT tiktok_oauth_session_status_check;
ALTER TABLE tiktok_oauth_session ADD CONSTRAINT tiktok_oauth_session_status_check
 CHECK(status IN ('NEW','EXCHANGING','READY','COMPLETED','FAILED','EXPIRED'));
ALTER TABLE tiktok_oauth_session ADD COLUMN match_status VARCHAR(24);
ALTER TABLE tiktok_oauth_session ADD COLUMN linked_creator_id UUID REFERENCES creator(id);
ALTER TABLE tiktok_oauth_session ADD COLUMN linked_master_id UUID REFERENCES creator_master(id);
ALTER TABLE tiktok_oauth_session ADD CONSTRAINT ck_tiktok_session_result CHECK(
 (status='COMPLETED' AND linked_creator_id IS NOT NULL AND linked_master_id IS NOT NULL AND match_status='MATCHED')
 OR (status<>'COMPLETED' AND linked_creator_id IS NULL AND linked_master_id IS NULL));
ALTER TABLE tiktok_oauth_session ADD CONSTRAINT ck_tiktok_match_status
 CHECK(match_status IS NULL OR match_status IN ('UNMATCHED','AMBIGUOUS','TARGET_MISMATCH','MATCHED'));
