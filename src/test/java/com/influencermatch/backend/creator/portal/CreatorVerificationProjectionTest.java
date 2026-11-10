package com.influencermatch.backend.creator.portal;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CreatorVerificationProjectionTest {
  UUID channel = UUID.randomUUID(), owner = UUID.randomUUID();
  LocalDateTime time = LocalDateTime.of(2026, 11, 9, 0, 0);

  Map<String, Object> row(String platform) {
    return Map.of(
        "source_type",
        "AUTHORIZED",
        "master_id",
        UUID.randomUUID(),
        "master_status",
        "ACTIVE",
        "owner_role",
        "CREATOR",
        "owner_status",
        "ACTIVE",
        "email_verified",
        true,
        "owner_id",
        owner,
        "platform",
        platform);
  }

  CreatorPortalProjection.Evidence evidence(String method, String status) {
    return new CreatorPortalProjection.Evidence(
        channel, owner, method, status, time, "ACCOUNT_VERIFICATION", false, null);
  }

  @Test
  void authorizationWithoutEvidencePreservesOwnerButIsLegacy() {
    var r = row("TIKTOK");
    assertThat(CreatorPortalProjection.verification(r, List.of()).status())
        .isEqualTo("LEGACY_UNVERIFIED");
    assertThat(r.get("owner_id")).isEqualTo(owner);
  }

  @Test
  void officialMethodsRequireCorrectPlatformAndShowSandbox() {
    for (var e :
        Map.of(
                "FACEBOOK",
                "META_OAUTH",
                "INSTAGRAM",
                "INSTAGRAM_OAUTH",
                "TIKTOK",
                "TIKTOK_OAUTH_SANDBOX")
            .entrySet())
      assertThat(
              CreatorPortalProjection.verification(
                      row(e.getKey()), List.of(evidence(e.getValue(), "ACTIVE")))
                  .verified())
          .isTrue();
    assertThat(
            CreatorPortalProjection.verification(
                    row("TIKTOK"), List.of(evidence("TIKTOK_OAUTH_SANDBOX", "ACTIVE")))
                .environment())
        .isEqualTo("SANDBOX");
    assertThat(
            CreatorPortalProjection.verification(
                    row("FACEBOOK"), List.of(evidence("INSTAGRAM_OAUTH", "ACTIVE")))
                .verified())
        .isFalse();
  }

  @Test
  void manualApprovalUsesActualReviewAndNotOauth() {
    var e =
        new CreatorPortalProjection.Evidence(
            channel, owner, "MANUAL_REVIEW", "ACTIVE", time, "CREATOR_CLAIM", false, null);
    var r = CreatorPortalProjection.verification(row("TIKTOK"), List.of(e));
    assertThat(r.method()).isEqualTo("MANUAL_REVIEW");
    assertThat(r.verifiedAt()).isEqualTo(time);
    assertThat(r.environment()).isEqualTo("MANUAL");
  }

  @Test
  void mockRevokedWrongOwnerAndUnknownNeverBecomeVerified() {
    assertThat(
            CreatorPortalProjection.verification(
                    row("TIKTOK"), List.of(evidence("OAUTH_SIMULATED_MOCK", "ACTIVE")))
                .status())
        .isEqualTo("SIMULATED");
    assertThat(
            CreatorPortalProjection.verification(
                    row("TIKTOK"), List.of(evidence("TIKTOK_OAUTH_SANDBOX", "REVOKED")))
                .verified())
        .isFalse();
    var wrong =
        new CreatorPortalProjection.Evidence(
            channel,
            UUID.randomUUID(),
            "TIKTOK_OAUTH_SANDBOX",
            "ACTIVE",
            time,
            "ACCOUNT_VERIFICATION",
            false,
            null);
    assertThat(CreatorPortalProjection.verification(row("TIKTOK"), List.of(wrong)).verified())
        .isFalse();
    assertThat(
            CreatorPortalProjection.verification(
                    row("TIKTOK"), List.of(evidence("OAUTH_OFFICIAL_SANDBOX", "ACTIVE")))
                .verified())
        .isFalse();
  }

  @Test
  void revokingManualDoesNotInvalidateIndependentOauthAndLatestOauthRevocationWins() {
    var oauth = evidence("TIKTOK_OAUTH_SANDBOX", "ACTIVE");
    var revoked =
        new CreatorPortalProjection.Evidence(
            channel,
            owner,
            "MANUAL_REVIEW",
            "REVOKED",
            time.plusHours(1),
            "CREATOR_CLAIM",
            false,
            null);
    assertThat(
            CreatorPortalProjection.verification(row("TIKTOK"), List.of(oauth, revoked)).verified())
        .isTrue();
    var oauthRevoked =
        new CreatorPortalProjection.Evidence(
            channel,
            owner,
            "TIKTOK_OAUTH_SANDBOX",
            "REVOKED",
            time.plusHours(2),
            "ACCOUNT_VERIFICATION",
            false,
            null);
    assertThat(
            CreatorPortalProjection.verification(
                    row("TIKTOK"), List.of(oauth, revoked, oauthRevoked))
                .verified())
        .isFalse();
  }

  @Test
  void revokedEvidenceWinsTiesAndExpiredTokenDoesNotRevokeOwnershipProof() {
    var live = evidence("TIKTOK_OAUTH_SANDBOX", "ACTIVE");
    assertThat(
            CreatorPortalProjection.verification(
                    row("TIKTOK"), List.of(live, evidence("TIKTOK_OAUTH_SANDBOX", "REVOKED")))
                .verified())
        .isFalse();
    var expired =
        new CreatorPortalProjection.Evidence(
            channel,
            owner,
            "TIKTOK_OAUTH_SANDBOX",
            "ACTIVE",
            time,
            "ACCOUNT_VERIFICATION",
            true,
            time.minusDays(1));
    assertThat(CreatorPortalProjection.verification(row("TIKTOK"), List.of(expired)).verified())
        .isTrue();
  }
}
