CREATE TABLE "platform" (
    "platformId" UUID PRIMARY KEY,
    "name" VARCHAR(50) NOT NULL,
    "providerCode" VARCHAR(50) NOT NULL,
    "active" BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT "uxPlatformName" UNIQUE ("name"),
    CONSTRAINT "uxPlatformProviderCode" UNIQUE ("providerCode")
);

CREATE TABLE "creator" (
    "creatorId" UUID PRIMARY KEY,
    "displayName" VARCHAR(200) NOT NULL,
    "location" VARCHAR(150),
    "status" VARCHAR(32) NOT NULL,
    "firstDiscoveredAt" TIMESTAMP NOT NULL,
    "updatedAt" TIMESTAMP NOT NULL
);

CREATE TABLE "creatorPlatformAccount" (
    "creatorAccountId" UUID PRIMARY KEY,
    "creatorId" UUID NOT NULL,
    "platformId" UUID NOT NULL,
    "externalId" VARCHAR(255) NOT NULL,
    "username" VARCHAR(255) NOT NULL,
    "profileUrl" VARCHAR(1000),
    "lastRefreshedAt" TIMESTAMP,
    CONSTRAINT "fkCreatorAccountCreator" FOREIGN KEY ("creatorId") REFERENCES "creator"("creatorId") ON DELETE RESTRICT,
    CONSTRAINT "fkCreatorAccountPlatform" FOREIGN KEY ("platformId") REFERENCES "platform"("platformId") ON DELETE RESTRICT,
    CONSTRAINT "uxCreatorAccountExternal" UNIQUE ("platformId", "externalId")
);

CREATE TABLE "category" (
    "categoryId" UUID PRIMARY KEY,
    "name" VARCHAR(120) NOT NULL,
    "parentCategoryId" UUID,
    CONSTRAINT "uxCategoryName" UNIQUE ("name"),
    CONSTRAINT "fkCategoryParent" FOREIGN KEY ("parentCategoryId") REFERENCES "category"("categoryId") ON DELETE RESTRICT
);

CREATE TABLE "creatorCategory" (
    "creatorId" UUID NOT NULL,
    "categoryId" UUID NOT NULL,
    "confidence" NUMERIC(5,4),
    "source" VARCHAR(100),
    CONSTRAINT "pkCreatorCategory" PRIMARY KEY ("creatorId", "categoryId"),
    CONSTRAINT "fkCreatorCategoryCreator" FOREIGN KEY ("creatorId") REFERENCES "creator"("creatorId") ON DELETE RESTRICT,
    CONSTRAINT "fkCreatorCategoryCategory" FOREIGN KEY ("categoryId") REFERENCES "category"("categoryId") ON DELETE RESTRICT,
    CONSTRAINT "ckCreatorCategoryConfidence" CHECK ("confidence" IS NULL OR ("confidence" >= 0 AND "confidence" <= 1))
);

CREATE TABLE "creatorContact" (
    "contactId" UUID PRIMARY KEY,
    "creatorAccountId" UUID NOT NULL,
    "addedByUserId" UUID,
    "contactType" VARCHAR(32) NOT NULL,
    "contactValue" VARCHAR(500) NOT NULL,
    "source" VARCHAR(100) NOT NULL,
    "visibility" VARCHAR(32) NOT NULL,
    "createdAt" TIMESTAMP NOT NULL,
    CONSTRAINT "fkCreatorContactAccount" FOREIGN KEY ("creatorAccountId") REFERENCES "creatorPlatformAccount"("creatorAccountId") ON DELETE RESTRICT,
    CONSTRAINT "fkCreatorContactUser" FOREIGN KEY ("addedByUserId") REFERENCES "user"("userId") ON DELETE RESTRICT
);

CREATE TABLE "publicMetricSnapshot" (
    "snapshotId" UUID PRIMARY KEY,
    "creatorAccountId" UUID NOT NULL,
    "followers" BIGINT,
    "avgViews" NUMERIC(19,2),
    "avgLikes" NUMERIC(19,2),
    "avgComments" NUMERIC(19,2),
    "avgShares" NUMERIC(19,2),
    "engagementRate" NUMERIC(7,4),
    "recentActivity" JSONB,
    "contentSummary" TEXT,
    "source" VARCHAR(100) NOT NULL,
    "sourceConfidence" NUMERIC(5,4),
    "collectedAt" TIMESTAMP NOT NULL,
    "dataStatus" VARCHAR(32) NOT NULL,
    CONSTRAINT "fkPublicMetricAccount" FOREIGN KEY ("creatorAccountId") REFERENCES "creatorPlatformAccount"("creatorAccountId") ON DELETE RESTRICT,
    CONSTRAINT "ckPublicMetricFollowers" CHECK ("followers" IS NULL OR "followers" >= 0),
    CONSTRAINT "ckPublicMetricEngagement" CHECK ("engagementRate" IS NULL OR "engagementRate" >= 0),
    CONSTRAINT "ckPublicMetricConfidence" CHECK ("sourceConfidence" IS NULL OR ("sourceConfidence" >= 0 AND "sourceConfidence" <= 1))
);

CREATE TABLE "recommendationConfig" (
    "configId" UUID PRIMARY KEY,
    "userId" UUID NOT NULL,
    "formulaVersion" VARCHAR(50) NOT NULL,
    "publicMetricWeight" NUMERIC(5,4) NOT NULL,
    "campaignContextWeight" NUMERIC(5,4) NOT NULL,
    "brandContextWeight" NUMERIC(5,4) NOT NULL,
    "historicalCampaignWeight" NUMERIC(5,4) NOT NULL,
    "active" BOOLEAN NOT NULL DEFAULT TRUE,
    "defaultConfig" BOOLEAN NOT NULL DEFAULT FALSE,
    "createdAt" TIMESTAMP NOT NULL,
    "updatedAt" TIMESTAMP NOT NULL,
    CONSTRAINT "fkRecommendationConfigUser" FOREIGN KEY ("userId") REFERENCES "user"("userId") ON DELETE RESTRICT,
    CONSTRAINT "ckRecommendationWeights" CHECK (
        "publicMetricWeight" BETWEEN 0 AND 1 AND "campaignContextWeight" BETWEEN 0 AND 1 AND
        "brandContextWeight" BETWEEN 0 AND 1 AND "historicalCampaignWeight" BETWEEN 0 AND 1 AND
        ("publicMetricWeight" + "campaignContextWeight" + "brandContextWeight" + "historicalCampaignWeight") > 0)
);

CREATE TABLE "recommendationRun" (
    "recommendationRunId" UUID PRIMARY KEY,
    "campaignId" UUID NOT NULL,
    "campaignContextId" UUID NOT NULL,
    "brandContextId" UUID NOT NULL,
    "configId" UUID NOT NULL,
    "status" VARCHAR(32) NOT NULL,
    "candidateCount" INTEGER NOT NULL DEFAULT 0,
    "eligibleCount" INTEGER NOT NULL DEFAULT 0,
    "resultCount" INTEGER NOT NULL DEFAULT 0,
    "startedAt" TIMESTAMP,
    "completedAt" TIMESTAMP,
    CONSTRAINT "fkRecommendationRunCampaign" FOREIGN KEY ("campaignId") REFERENCES "campaign"("campaignId") ON DELETE RESTRICT,
    CONSTRAINT "fkRecommendationRunM3" FOREIGN KEY ("campaignContextId") REFERENCES "campaignContextM3"(id) ON DELETE RESTRICT,
    CONSTRAINT "fkRecommendationRunM4" FOREIGN KEY ("brandContextId") REFERENCES "brandContextM4"(id) ON DELETE RESTRICT,
    CONSTRAINT "fkRecommendationRunConfig" FOREIGN KEY ("configId") REFERENCES "recommendationConfig"("configId") ON DELETE RESTRICT,
    CONSTRAINT "ckRecommendationCounts" CHECK ("candidateCount" >= 0 AND "eligibleCount" >= 0 AND "resultCount" >= 0)
);

CREATE TABLE "recommendationResult" (
    "resultId" UUID PRIMARY KEY,
    "recommendationRunId" UUID NOT NULL,
    "creatorId" UUID NOT NULL,
    "snapshotId" UUID,
    "matchScore" NUMERIC(7,4) NOT NULL,
    "confidence" NUMERIC(5,4) NOT NULL,
    "scoreBreakdown" JSONB NOT NULL,
    "missingEvidence" JSONB NOT NULL DEFAULT '[]'::jsonb,
    "estimatedFeeMin" NUMERIC(19,2),
    "estimatedFeeMax" NUMERIC(19,2),
    "feeCurrency" VARCHAR(3),
    "feeConfidence" VARCHAR(32),
    "explanation" TEXT,
    "rankPosition" INTEGER NOT NULL,
    "generatedAt" TIMESTAMP NOT NULL,
    CONSTRAINT "fkRecommendationResultRun" FOREIGN KEY ("recommendationRunId") REFERENCES "recommendationRun"("recommendationRunId") ON DELETE RESTRICT,
    CONSTRAINT "fkRecommendationResultCreator" FOREIGN KEY ("creatorId") REFERENCES "creator"("creatorId") ON DELETE RESTRICT,
    CONSTRAINT "fkRecommendationResultSnapshot" FOREIGN KEY ("snapshotId") REFERENCES "publicMetricSnapshot"("snapshotId") ON DELETE RESTRICT,
    CONSTRAINT "uxRecommendationResultCreator" UNIQUE ("recommendationRunId", "creatorId"),
    CONSTRAINT "uxRecommendationResultRank" UNIQUE ("recommendationRunId", "rankPosition"),
    CONSTRAINT "ckRecommendationScore" CHECK ("matchScore" BETWEEN 0 AND 100),
    CONSTRAINT "ckRecommendationConfidence" CHECK ("confidence" BETWEEN 0 AND 1),
    CONSTRAINT "ckRecommendationFeeRange" CHECK ("estimatedFeeMax" IS NULL OR "estimatedFeeMin" IS NULL OR "estimatedFeeMax" >= "estimatedFeeMin"),
    CONSTRAINT "ckRecommendationRank" CHECK ("rankPosition" > 0)
);

CREATE TABLE "shortlistItem" (
    "shortlistItemId" UUID PRIMARY KEY,
    "campaignId" UUID NOT NULL,
    "creatorId" UUID NOT NULL,
    "recommendationResultId" UUID,
    "userId" UUID NOT NULL,
    "priority" VARCHAR(16) NOT NULL,
    "note" TEXT,
    "addedAt" TIMESTAMP NOT NULL,
    CONSTRAINT "fkShortlistCampaign" FOREIGN KEY ("campaignId") REFERENCES "campaign"("campaignId") ON DELETE RESTRICT,
    CONSTRAINT "fkShortlistCreator" FOREIGN KEY ("creatorId") REFERENCES "creator"("creatorId") ON DELETE RESTRICT,
    CONSTRAINT "fkShortlistResult" FOREIGN KEY ("recommendationResultId") REFERENCES "recommendationResult"("resultId") ON DELETE SET NULL,
    CONSTRAINT "fkShortlistUser" FOREIGN KEY ("userId") REFERENCES "user"("userId") ON DELETE RESTRICT,
    CONSTRAINT "uxShortlistCampaignCreator" UNIQUE ("campaignId", "creatorId")
);

CREATE TABLE "relationship" (
    "relationshipId" UUID PRIMARY KEY,
    "userId" UUID NOT NULL,
    "creatorId" UUID NOT NULL,
    "campaignId" UUID NOT NULL,
    "shortlistItemId" UUID,
    "stage" VARCHAR(32) NOT NULL,
    "contactMethod" VARCHAR(100),
    "quotedFee" NUMERIC(19,2),
    "agreedFee" NUMERIC(19,2),
    "currency" VARCHAR(3),
    "lastContactAt" TIMESTAMP,
    "notes" TEXT,
    "nextAction" VARCHAR(500),
    "createdAt" TIMESTAMP NOT NULL,
    "updatedAt" TIMESTAMP NOT NULL,
    CONSTRAINT "fkRelationshipUser" FOREIGN KEY ("userId") REFERENCES "user"("userId") ON DELETE RESTRICT,
    CONSTRAINT "fkRelationshipCreator" FOREIGN KEY ("creatorId") REFERENCES "creator"("creatorId") ON DELETE RESTRICT,
    CONSTRAINT "fkRelationshipCampaign" FOREIGN KEY ("campaignId") REFERENCES "campaign"("campaignId") ON DELETE RESTRICT,
    CONSTRAINT "fkRelationshipShortlist" FOREIGN KEY ("shortlistItemId") REFERENCES "shortlistItem"("shortlistItemId") ON DELETE SET NULL,
    CONSTRAINT "uxRelationshipCampaignCreator" UNIQUE ("campaignId", "creatorId")
);

CREATE TABLE "collaboration" (
    "collaborationId" UUID PRIMARY KEY,
    "relationshipId" UUID NOT NULL,
    "campaignId" UUID NOT NULL,
    "creatorId" UUID NOT NULL,
    "agreedFee" NUMERIC(19,2),
    "currency" VARCHAR(3),
    "status" VARCHAR(32) NOT NULL,
    "publishedContentUrl" VARCHAR(1000),
    "startAt" TIMESTAMP,
    "completedAt" TIMESTAMP,
    "note" TEXT,
    "paymentStatus" VARCHAR(32),
    "updatedAt" TIMESTAMP NOT NULL,
    CONSTRAINT "uxCollaborationRelationship" UNIQUE ("relationshipId"),
    CONSTRAINT "fkCollaborationRelationship" FOREIGN KEY ("relationshipId") REFERENCES "relationship"("relationshipId") ON DELETE RESTRICT,
    CONSTRAINT "fkCollaborationCampaign" FOREIGN KEY ("campaignId") REFERENCES "campaign"("campaignId") ON DELETE RESTRICT,
    CONSTRAINT "fkCollaborationCreator" FOREIGN KEY ("creatorId") REFERENCES "creator"("creatorId") ON DELETE RESTRICT
);

CREATE TABLE "campaignOutcome" (
    "outcomeId" UUID PRIMARY KEY,
    "collaborationId" UUID NOT NULL,
    "kpiType" VARCHAR(100),
    "kpiTarget" NUMERIC(19,2),
    "kpiActual" NUMERIC(19,2),
    "resultNote" TEXT,
    "recordedAt" TIMESTAMP,
    "actualFee" NUMERIC(19,2),
    "currency" VARCHAR(3),
    "revenue" NUMERIC(19,2),
    "roi" NUMERIC(12,4),
    "evidenceQuality" VARCHAR(32),
    "completedAt" TIMESTAMP,
    CONSTRAINT "uxOutcomeCollaboration" UNIQUE ("collaborationId"),
    CONSTRAINT "fkOutcomeCollaboration" FOREIGN KEY ("collaborationId") REFERENCES "collaboration"("collaborationId") ON DELETE RESTRICT
);

CREATE TABLE "evidenceAsset" (
    "evidenceAssetId" UUID PRIMARY KEY,
    "outcomeId" UUID NOT NULL,
    "assetType" VARCHAR(50) NOT NULL,
    "storageUrl" VARCHAR(1000) NOT NULL,
    "source" VARCHAR(100),
    "uploadedAt" TIMESTAMP NOT NULL,
    CONSTRAINT "fkEvidenceAssetOutcome" FOREIGN KEY ("outcomeId") REFERENCES "campaignOutcome"("outcomeId") ON DELETE CASCADE
);

CREATE TABLE "creatorReview" (
    "reviewId" UUID PRIMARY KEY,
    "outcomeId" UUID NOT NULL,
    "collaborationId" UUID NOT NULL,
    "reviewedByUserId" UUID NOT NULL,
    "overallRating" SMALLINT NOT NULL,
    "brandFitRating" SMALLINT NOT NULL,
    "wouldCollaborateAgain" BOOLEAN,
    "note" TEXT,
    "reviewedAt" TIMESTAMP NOT NULL,
    CONSTRAINT "uxCreatorReviewOutcome" UNIQUE ("outcomeId"),
    CONSTRAINT "uxCreatorReviewCollaboration" UNIQUE ("collaborationId"),
    CONSTRAINT "fkCreatorReviewOutcome" FOREIGN KEY ("outcomeId") REFERENCES "campaignOutcome"("outcomeId") ON DELETE RESTRICT,
    CONSTRAINT "fkCreatorReviewCollaboration" FOREIGN KEY ("collaborationId") REFERENCES "collaboration"("collaborationId") ON DELETE RESTRICT,
    CONSTRAINT "fkCreatorReviewUser" FOREIGN KEY ("reviewedByUserId") REFERENCES "user"("userId") ON DELETE RESTRICT,
    CONSTRAINT "ckCreatorReviewRating" CHECK ("overallRating" BETWEEN 1 AND 5 AND "brandFitRating" BETWEEN 1 AND 5)
);

CREATE TABLE "historicalEvidence" (
    "historicalEvidenceId" UUID PRIMARY KEY,
    "creatorId" UUID NOT NULL,
    "collaborationId" UUID,
    "outcomeId" UUID,
    "evidenceType" VARCHAR(32) NOT NULL,
    "evidenceValue" JSONB,
    "textValue" TEXT,
    "evidenceQuality" VARCHAR(32) NOT NULL,
    "generatedAt" TIMESTAMP NOT NULL,
    CONSTRAINT "fkHistoricalEvidenceCreator" FOREIGN KEY ("creatorId") REFERENCES "creator"("creatorId") ON DELETE RESTRICT,
    CONSTRAINT "fkHistoricalEvidenceCollaboration" FOREIGN KEY ("collaborationId") REFERENCES "collaboration"("collaborationId") ON DELETE RESTRICT,
    CONSTRAINT "fkHistoricalEvidenceOutcome" FOREIGN KEY ("outcomeId") REFERENCES "campaignOutcome"("outcomeId") ON DELETE RESTRICT
);

CREATE TABLE "brandPattern" (
    "brandPatternId" UUID PRIMARY KEY,
    "brandProfileId" UUID NOT NULL,
    "platform" VARCHAR(50),
    "niche" VARCHAR(120),
    "averageSuccessfulScore" NUMERIC(7,4),
    "averageAgreedFee" NUMERIC(19,2),
    "currency" VARCHAR(3),
    "preferredEngagementRate" NUMERIC(7,4),
    "patternType" VARCHAR(100) NOT NULL,
    "patternValue" JSONB NOT NULL,
    "confidence" NUMERIC(5,4) NOT NULL,
    "evidenceCount" INTEGER NOT NULL,
    "learnedAt" TIMESTAMP NOT NULL,
    CONSTRAINT "fkBrandPatternProfile" FOREIGN KEY ("brandProfileId") REFERENCES "brandProfile"("brandProfileId") ON DELETE RESTRICT,
    CONSTRAINT "ckBrandPatternConfidence" CHECK ("confidence" BETWEEN 0 AND 1),
    CONSTRAINT "ckBrandPatternEvidenceCount" CHECK ("evidenceCount" >= 0)
);

CREATE TABLE "plan" (
    "planId" UUID PRIMARY KEY,
    "planName" VARCHAR(100) NOT NULL,
    "price" NUMERIC(19,2) NOT NULL,
    "currency" VARCHAR(3) NOT NULL,
    "durationDays" INTEGER NOT NULL,
    "campaignQuota" INTEGER NOT NULL,
    "recommendationQuota" INTEGER NOT NULL,
    "refreshQuota" INTEGER NOT NULL,
    "description" TEXT,
    "status" VARCHAR(32) NOT NULL,
    "createdAt" TIMESTAMP NOT NULL,
    CONSTRAINT "uxPlanName" UNIQUE ("planName"),
    CONSTRAINT "ckPlanValues" CHECK ("price" >= 0 AND "durationDays" > 0 AND "campaignQuota" >= 0 AND "recommendationQuota" >= 0 AND "refreshQuota" >= 0)
);

CREATE TABLE "subscription" (
    "subscriptionId" UUID PRIMARY KEY,
    "brandProfileId" UUID NOT NULL,
    "planId" UUID NOT NULL,
    "startDate" DATE NOT NULL,
    "expirationDate" DATE NOT NULL,
    "status" VARCHAR(32) NOT NULL,
    "usedCampaignQuota" INTEGER NOT NULL DEFAULT 0,
    "usedRecommendationQuota" INTEGER NOT NULL DEFAULT 0,
    "usedRefreshQuota" INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT "fkSubscriptionBrandProfile" FOREIGN KEY ("brandProfileId") REFERENCES "brandProfile"("brandProfileId") ON DELETE RESTRICT,
    CONSTRAINT "fkSubscriptionPlan" FOREIGN KEY ("planId") REFERENCES "plan"("planId") ON DELETE RESTRICT,
    CONSTRAINT "ckSubscriptionDates" CHECK ("expirationDate" >= "startDate"),
    CONSTRAINT "ckSubscriptionUsage" CHECK ("usedCampaignQuota" >= 0 AND "usedRecommendationQuota" >= 0 AND "usedRefreshQuota" >= 0)
);

CREATE TABLE "payment" (
    "paymentId" UUID PRIMARY KEY,
    "brandProfileId" UUID NOT NULL,
    "subscriptionId" UUID NOT NULL,
    "amount" NUMERIC(19,2) NOT NULL,
    "currency" VARCHAR(3) NOT NULL,
    "paymentGateway" VARCHAR(100) NOT NULL,
    "transactionCode" VARCHAR(255),
    "status" VARCHAR(32) NOT NULL,
    "paidAt" TIMESTAMP,
    "failureReason" TEXT,
    "createdAt" TIMESTAMP NOT NULL,
    CONSTRAINT "uxPaymentTransactionCode" UNIQUE ("transactionCode"),
    CONSTRAINT "fkPaymentBrandProfile" FOREIGN KEY ("brandProfileId") REFERENCES "brandProfile"("brandProfileId") ON DELETE RESTRICT,
    CONSTRAINT "fkPaymentSubscription" FOREIGN KEY ("subscriptionId") REFERENCES "subscription"("subscriptionId") ON DELETE RESTRICT,
    CONSTRAINT "ckPaymentAmount" CHECK ("amount" >= 0)
);

CREATE TABLE "auditLog" (
    "auditLogId" UUID PRIMARY KEY,
    "userId" UUID,
    "action" VARCHAR(100) NOT NULL,
    "entityType" VARCHAR(100) NOT NULL,
    "entityId" UUID,
    "oldValue" JSONB,
    "newValue" JSONB,
    "description" TEXT,
    "ipAddress" VARCHAR(64),
    "createdAt" TIMESTAMP NOT NULL,
    CONSTRAINT "fkAuditLogUser" FOREIGN KEY ("userId") REFERENCES "user"("userId") ON DELETE RESTRICT
);

CREATE TABLE "notification" (
    "notificationId" UUID PRIMARY KEY,
    "userId" UUID NOT NULL,
    "type" VARCHAR(100) NOT NULL,
    "title" VARCHAR(255) NOT NULL,
    "message" TEXT NOT NULL,
    "entityType" VARCHAR(100),
    "entityId" UUID,
    "read" BOOLEAN NOT NULL DEFAULT FALSE,
    "readAt" TIMESTAMP,
    "expiresAt" TIMESTAMP,
    "createdAt" TIMESTAMP NOT NULL,
    CONSTRAINT "fkNotificationUser" FOREIGN KEY ("userId") REFERENCES "user"("userId") ON DELETE RESTRICT
);

CREATE UNIQUE INDEX "uxRecommendationDefaultConfig" ON "recommendationConfig"("userId") WHERE "defaultConfig" = TRUE;
CREATE UNIQUE INDEX "uxSubscriptionActiveBrand" ON "subscription"("brandProfileId") WHERE "status" = 'ACTIVE';
CREATE INDEX "ixCreatorAccountCreator" ON "creatorPlatformAccount"("creatorId");
CREATE INDEX "ixPublicMetricAccountCollected" ON "publicMetricSnapshot"("creatorAccountId", "collectedAt" DESC);
CREATE INDEX "ixRecommendationRunCampaignStatus" ON "recommendationRun"("campaignId", "status");
CREATE INDEX "ixRecommendationResultCreator" ON "recommendationResult"("creatorId");
CREATE INDEX "ixRelationshipStage" ON "relationship"("stage");
CREATE INDEX "ixCollaborationStatus" ON "collaboration"("status");
CREATE INDEX "ixHistoricalEvidenceCreator" ON "historicalEvidence"("creatorId", "evidenceType");
CREATE INDEX "ixBrandPatternProfile" ON "brandPattern"("brandProfileId");
CREATE INDEX "ixPaymentStatus" ON "payment"("status");
CREATE INDEX "ixNotificationUnread" ON "notification"("userId", "createdAt" DESC) WHERE "read" = FALSE;
