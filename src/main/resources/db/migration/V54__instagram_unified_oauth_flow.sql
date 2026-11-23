-- Extend the existing session constraint; do not modify applied V21.
ALTER TABLE meta_oauth_session DROP CONSTRAINT ck_meta_oauth_flow;
ALTER TABLE meta_oauth_session ADD CONSTRAINT ck_meta_oauth_flow CHECK (
    oauth_flow IN ('FACEBOOK_LOGIN', 'INSTAGRAM_LOGIN', 'INSTAGRAM_UNIFIED_LOGIN')
    AND (oauth_flow = 'FACEBOOK_LOGIN' OR platform = 'INSTAGRAM')
);
