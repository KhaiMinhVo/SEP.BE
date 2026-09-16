-- PostgreSQL folds unquoted names to lower-case, so camelCase identifiers are quoted.

ALTER TABLE users RENAME TO "user";
ALTER TABLE refresh_tokens RENAME TO "refreshToken";
ALTER TABLE brand_profiles RENAME TO "brandProfile";
ALTER TABLE brand_context_m4 RENAME TO "brandContextM4";
ALTER TABLE campaigns RENAME TO "campaign";
ALTER TABLE campaign_context_m3 RENAME TO "campaignContextM3";

ALTER TABLE "user" RENAME COLUMN user_id TO "userId";
ALTER TABLE "user" RENAME COLUMN password_hash TO "passwordHash";
ALTER TABLE "user" RENAME COLUMN full_name TO "fullName";
ALTER TABLE "user" RENAME COLUMN last_login_at TO "lastLoginAt";
ALTER TABLE "user" RENAME COLUMN created_at TO "createdAt";
ALTER TABLE "user" RENAME COLUMN updated_at TO "updatedAt";

ALTER TABLE "refreshToken" RENAME COLUMN user_id TO "userId";
ALTER TABLE "refreshToken" RENAME COLUMN token_hash TO "tokenHash";
ALTER TABLE "refreshToken" RENAME COLUMN expires_at TO "expiresAt";
ALTER TABLE "refreshToken" RENAME COLUMN revoked_at TO "revokedAt";
ALTER TABLE "refreshToken" RENAME COLUMN replaced_by_token_id TO "replacedByTokenId";
ALTER TABLE "refreshToken" RENAME COLUMN created_at TO "createdAt";

ALTER TABLE "brandProfile" RENAME COLUMN brand_profile_id TO "brandProfileId";
ALTER TABLE "brandProfile" RENAME COLUMN user_id TO "userId";
ALTER TABLE "brandProfile" RENAME COLUMN business_name TO "businessName";
ALTER TABLE "brandProfile" RENAME COLUMN product_categories TO "productCategories";
ALTER TABLE "brandProfile" RENAME COLUMN target_markets TO "targetMarkets";
ALTER TABLE "brandProfile" RENAME COLUMN target_audiences TO "targetAudiences";
ALTER TABLE "brandProfile" RENAME COLUMN brand_tone TO "brandTone";
ALTER TABLE "brandProfile" RENAME COLUMN preferred_platforms TO "preferredPlatforms";
ALTER TABLE "brandProfile" RENAME COLUMN created_at TO "createdAt";
ALTER TABLE "brandProfile" RENAME COLUMN updated_at TO "updatedAt";

ALTER TABLE "brandContextM4" RENAME COLUMN brand_profile_id TO "brandProfileId";
ALTER TABLE "brandContextM4" RENAME COLUMN context_version TO "contextVersion";
ALTER TABLE "brandContextM4" RENAME COLUMN profile_context TO "profileContext";
ALTER TABLE "brandContextM4" RENAME COLUMN learned_patterns TO "learnedPatterns";
ALTER TABLE "brandContextM4" RENAME COLUMN evidence_refs TO "evidenceRefs";
ALTER TABLE "brandContextM4" RENAME COLUMN created_at TO "createdAt";
ALTER TABLE "brandContextM4" RENAME COLUMN updated_at TO "updatedAt";

ALTER TABLE "campaign" RENAME COLUMN campaign_id TO "campaignId";
ALTER TABLE "campaign" RENAME COLUMN brand_profile_id TO "brandProfileId";
ALTER TABLE "campaign" RENAME COLUMN product_service TO "productService";
ALTER TABLE "campaign" RENAME COLUMN target_audiences TO "targetAudiences";
ALTER TABLE "campaign" RENAME COLUMN follower_min TO "followerMin";
ALTER TABLE "campaign" RENAME COLUMN follower_max TO "followerMax";
ALTER TABLE "campaign" RENAME COLUMN content_type TO "contentType";
ALTER TABLE "campaign" RENAME COLUMN budget_min TO "budgetMin";
ALTER TABLE "campaign" RENAME COLUMN budget_max TO "budgetMax";
ALTER TABLE "campaign" RENAME COLUMN start_date TO "startDate";
ALTER TABLE "campaign" RENAME COLUMN end_date TO "endDate";
ALTER TABLE "campaign" RENAME COLUMN created_at TO "createdAt";
ALTER TABLE "campaign" RENAME COLUMN updated_at TO "updatedAt";

ALTER TABLE "campaignContextM3" RENAME COLUMN campaign_id TO "campaignId";
ALTER TABLE "campaignContextM3" RENAME COLUMN context_version TO "contextVersion";
ALTER TABLE "campaignContextM3" RENAME COLUMN context_data TO "contextData";
ALTER TABLE "campaignContextM3" RENAME COLUMN archived_at TO "archivedAt";
ALTER TABLE "campaignContextM3" RENAME COLUMN created_at TO "createdAt";
ALTER TABLE "campaignContextM3" RENAME COLUMN updated_at TO "updatedAt";

ALTER TABLE "user" RENAME CONSTRAINT users_pkey TO "pkUser";
ALTER TABLE "user" RENAME CONSTRAINT users_email_key TO "uxUserEmail";

ALTER TABLE "refreshToken" RENAME CONSTRAINT refresh_tokens_pkey TO "pkRefreshToken";
ALTER TABLE "refreshToken" RENAME CONSTRAINT refresh_tokens_jti_key TO "uxRefreshTokenJti";
ALTER TABLE "refreshToken" RENAME CONSTRAINT refresh_tokens_token_hash_key TO "uxRefreshTokenTokenHash";
ALTER TABLE "refreshToken" RENAME CONSTRAINT refresh_tokens_user_id_fkey TO "fkRefreshTokenUser";
ALTER TABLE "refreshToken" RENAME CONSTRAINT refresh_tokens_replaced_by_token_id_fkey TO "fkRefreshTokenReplacement";

ALTER TABLE "brandProfile" RENAME CONSTRAINT brand_profiles_pkey TO "pkBrandProfile";
ALTER TABLE "brandProfile" RENAME CONSTRAINT fk_brand_profiles_owner TO "fkBrandProfileUser";
ALTER TABLE "brandProfile" RENAME CONSTRAINT ux_brand_profiles_owner TO "uxBrandProfileUser";

ALTER TABLE "brandContextM4" RENAME CONSTRAINT brand_context_m4_pkey TO "pkBrandContextM4";
ALTER TABLE "brandContextM4" RENAME CONSTRAINT ck_brand_context_version TO "ckBrandContextVersion";
ALTER TABLE "brandContextM4" RENAME CONSTRAINT fk_brand_context_profile TO "fkBrandContextBrandProfile";
ALTER TABLE "brandContextM4" RENAME CONSTRAINT ux_brand_context_profile TO "uxBrandContextBrandProfile";

ALTER TABLE "campaign" RENAME CONSTRAINT campaigns_pkey TO "pkCampaign";
ALTER TABLE "campaign" RENAME CONSTRAINT ck_campaign_budget TO "ckCampaignBudget";
ALTER TABLE "campaign" RENAME CONSTRAINT ck_campaign_budget_range TO "ckCampaignBudgetRange";
ALTER TABLE "campaign" RENAME CONSTRAINT ck_campaign_followers TO "ckCampaignFollowers";
ALTER TABLE "campaign" RENAME CONSTRAINT ck_campaign_followers_range TO "ckCampaignFollowerRange";
ALTER TABLE "campaign" RENAME CONSTRAINT ck_campaign_timeline TO "ckCampaignTimeline";
ALTER TABLE "campaign" RENAME CONSTRAINT fk_campaigns_brand_profile TO "fkCampaignBrandProfile";

ALTER TABLE "campaignContextM3" RENAME CONSTRAINT campaign_context_m3_pkey TO "pkCampaignContextM3";
ALTER TABLE "campaignContextM3" RENAME CONSTRAINT campaign_context_m3_campaign_id_context_version_key TO "uxCampaignContextVersion";
ALTER TABLE "campaignContextM3" RENAME CONSTRAINT campaign_context_m3_campaign_id_fkey TO "fkCampaignContextCampaign";
ALTER TABLE "campaignContextM3" RENAME CONSTRAINT ck_campaign_context_version TO "ckCampaignContextVersion";

ALTER INDEX ix_refresh_tokens_user RENAME TO "ixRefreshTokenUser";
ALTER INDEX ix_refresh_tokens_expiry RENAME TO "ixRefreshTokenExpiry";
ALTER INDEX ix_refresh_tokens_active RENAME TO "ixRefreshTokenActive";
ALTER INDEX ix_campaigns_brand_profile RENAME TO "ixCampaignBrandProfile";
ALTER INDEX ix_campaigns_profile_status RENAME TO "ixCampaignProfileStatus";
ALTER INDEX ux_campaign_context_one_active RENAME TO "uxCampaignContextOneActive";
