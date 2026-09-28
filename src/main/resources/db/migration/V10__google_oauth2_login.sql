ALTER TABLE "user" ALTER COLUMN "passwordHash" DROP NOT NULL;

CREATE TABLE "externalIdentity" (
    "externalIdentityId" UUID PRIMARY KEY,
    "userId" UUID NOT NULL,
    "provider" VARCHAR(30) NOT NULL,
    "providerSubject" VARCHAR(255) NOT NULL,
    "providerEmail" VARCHAR(255) NOT NULL,
    "emailVerified" BOOLEAN NOT NULL,
    "createdAt" TIMESTAMP NOT NULL,
    "updatedAt" TIMESTAMP NOT NULL,
    CONSTRAINT "fkExternalIdentityUser" FOREIGN KEY ("userId") REFERENCES "user"("userId") ON DELETE RESTRICT,
    CONSTRAINT "uxExternalIdentityProviderSubject" UNIQUE ("provider", "providerSubject"),
    CONSTRAINT "uxExternalIdentityUserProvider" UNIQUE ("userId", "provider")
);

CREATE INDEX "ixExternalIdentityUser" ON "externalIdentity"("userId");

CREATE TABLE "authExchangeCode" (
    "authExchangeCodeId" UUID PRIMARY KEY,
    "userId" UUID NOT NULL,
    "codeHash" VARCHAR(64) NOT NULL,
    "expiresAt" TIMESTAMP NOT NULL,
    "consumedAt" TIMESTAMP NULL,
    "createdAt" TIMESTAMP NOT NULL,
    "version" BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT "fkAuthExchangeCodeUser" FOREIGN KEY ("userId") REFERENCES "user"("userId") ON DELETE CASCADE,
    CONSTRAINT "uxAuthExchangeCodeHash" UNIQUE ("codeHash")
);

CREATE INDEX "ixAuthExchangeCodeUser" ON "authExchangeCode"("userId");
CREATE INDEX "ixAuthExchangeCodeExpiry" ON "authExchangeCode"("expiresAt");
