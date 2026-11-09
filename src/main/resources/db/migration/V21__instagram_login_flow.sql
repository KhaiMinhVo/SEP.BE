ALTER TABLE meta_oauth_session
    ADD COLUMN oauth_flow VARCHAR(32) NOT NULL DEFAULT 'FACEBOOK_LOGIN';
ALTER TABLE meta_oauth_session ADD CONSTRAINT ck_meta_oauth_flow CHECK (
    oauth_flow IN ('FACEBOOK_LOGIN', 'INSTAGRAM_LOGIN')
    AND (oauth_flow <> 'INSTAGRAM_LOGIN' OR platform = 'INSTAGRAM')
);
