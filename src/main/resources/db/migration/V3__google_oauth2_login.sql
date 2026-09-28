ALTER TABLE "user" ALTER COLUMN "password_hash" DROP NOT NULL;

CREATE TABLE "external_identity" (
    "id" UUID PRIMARY KEY,
    "user_id" UUID NOT NULL,
    "provider" VARCHAR(30) NOT NULL,
    "provider_subject" VARCHAR(255) NOT NULL,
    "provider_email" VARCHAR(255) NOT NULL,
    "email_verified" BOOLEAN NOT NULL,
    "created_at" TIMESTAMP NOT NULL,
    "updated_at" TIMESTAMP NOT NULL,
    CONSTRAINT "fk_external_identity_user" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE RESTRICT,
    CONSTRAINT "ux_external_identity_provider_subject" UNIQUE ("provider", "provider_subject"),
    CONSTRAINT "ux_external_identity_user_provider" UNIQUE ("user_id", "provider")
);

CREATE INDEX "ix_external_identity_user" ON "external_identity"("user_id");

CREATE TABLE "auth_exchange_code" (
    "id" UUID PRIMARY KEY,
    "user_id" UUID NOT NULL,
    "code_hash" VARCHAR(64) NOT NULL,
    "expires_at" TIMESTAMP NOT NULL,
    "consumed_at" TIMESTAMP NULL,
    "created_at" TIMESTAMP NOT NULL,
    "version" BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT "fk_auth_exchange_code_user" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE,
    CONSTRAINT "ux_auth_exchange_code_hash" UNIQUE ("code_hash")
);

CREATE INDEX "ix_auth_exchange_code_user" ON "auth_exchange_code"("user_id");
CREATE INDEX "ix_auth_exchange_code_expiry" ON "auth_exchange_code"("expires_at");
