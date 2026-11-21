ALTER TABLE cooperation_email_job ADD COLUMN delivery_method varchar(20);
ALTER TABLE cooperation_email_job ADD CONSTRAINT ck_cooperation_delivery_method
  CHECK (delivery_method IS NULL OR delivery_method IN ('PORTAL_EMAIL','PORTAL_ONLY','EMAIL_ONLY','MANUAL_LINK'));
