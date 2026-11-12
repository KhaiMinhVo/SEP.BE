package com.influencermatch.backend.relationship;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.CreatorInvitationService;
import com.influencermatch.backend.support.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Outreach no longer sends Claim invitations; these scenarios now exercise PostgreSQL, not Mockito
 * transactions.
 */
class ShortlistOutreachPhase4Test extends PostgresApiTestSupport {
  @Autowired CreatorRepository creators;
  @Autowired CreatorMasterRepository masters;
  @Autowired PublicCreatorMetricRepository metrics;
  @MockBean VerificationEmailSender sender;
  @MockBean CreatorInvitationService claimInvitations;
  User brand;
  UUID campaign;
  Creator c;
  UUID item;

  @BeforeEach
  void setup() throws Exception {
    brand = account(Role.BRAND);
    campaign = createCampaign(brand, createBrand(brand));
    c =
        creators.saveAndFlush(
            Creator.builder()
                .platform("TIKTOK")
                .externalId(UUID.randomUUID().toString())
                .userName("outreach")
                .displayName("Creator")
                .profileUrl("https://www.tiktok.com/@outreach")
                .build());
    item =
        UUID.fromString(
            call(
                    brand,
                    post("/campaigns/" + campaign + "/shortlist"),
                    Map.of("creatorId", c.getId()),
                    200)
                .path("data")
                .path("id")
                .asText());
  }

  Map<String, Object> body() {
    return Map.of(
        "shortlistItemIds",
        List.of(item),
        "proposedFee",
        100000,
        "currency",
        "VND",
        "outreachMessage",
        "One review video",
        "deliverables",
        List.of(Map.of("creatorId", c.getId(), "contentType", "Review", "quantity", 1, "dueAt", "2027-01-01T00:00:00Z", "acceptanceChecklist", List.of("Brief satisfied"), "maxRevisionRounds", 2)));
  }

  com.fasterxml.jackson.databind.JsonNode outreach() throws Exception {
    return call(brand, post("/campaigns/" + campaign + "/shortlist/outreach"), body(), 200)
        .path("data")
        .path("items")
        .get(0);
  }

  @Test
  void testOutreach_Success_PublicCreatorWithEmail() throws Exception {
    metrics.saveAndFlush(
        PublicCreatorMetric.builder()
            .creator(c)
            .collectedAt(LocalDateTime.now())
            .contact("new@example.test")
            .freshnessStatus(com.influencermatch.backend.creator.enums.MetricDataStatus.FRESH)
            .build());
    var result = outreach();
    assertThat(result.path("contactStatus").asText()).isEqualTo("QUEUED");
    assertThat(result.path("relationshipId").asText()).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "select stage from relationship where id=?",
                String.class,
                UUID.fromString(result.path("relationshipId").asText())))
        .isEqualTo("NEGOTIATING");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from cooperation_invitation where id=?",
                Long.class,
                UUID.fromString(result.path("invitationId").asText())))
        .isOne();
    verifyNoInteractions(claimInvitations);
    assertThat(creators.findById(c.getId()).orElseThrow().getMaster()).isNull();
  }

  @Test
  void testOutreach_Success_AuthorizedCreator() throws Exception {
    var user = account(Role.CREATOR);
    var master =
        masters.saveAndFlush(CreatorMaster.builder().user(user).canonicalName("Owner").build());
    jdbc.update(
        "update creator set master_id=?,source_type='AUTHORIZED',authorized_at=now() where id=?",
        master.getId(),
        c.getId());
    var result = outreach();
    assertThat(result.path("contactStatus").asText()).isEqualTo("QUEUED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from notification where user_id=? and"
                    + " type='COOPERATION_INVITATION'",
                Long.class,
                user.getId()))
        .isOne();
    verifyNoInteractions(claimInvitations);
  }

  @Test
  void testOutreach_PendingInvitationExists() throws Exception {
    var first = outreach();
    var second = outreach();
    assertThat(second.path("contactStatus").asText()).isEqualTo("ALREADY_EXISTS");
    assertThat(second.path("dealId")).isEqualTo(first.path("dealId"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from cooperation_invitation where deal_id=?",
                Long.class,
                UUID.fromString(first.path("dealId").asText())))
        .isOne();
    verifyNoInteractions(claimInvitations);
  }

  @Test
  void testOutreach_ManualSocialLink() throws Exception {
    var result = outreach();
    assertThat(result.path("contactStatus").asText()).isEqualTo("MANUAL_REQUIRED");
    assertThat(result.path("invitationId").asText()).isNotBlank();
    var link =
        call(
            brand,
            get(
                "/cooperation-invitations/"
                    + result.path("invitationId").asText()
                    + "/manual-link"),
            null,
            200);
    assertThat(link.path("data").path("link").asText()).contains("/cooperation-invitations?token=");
    verifyNoInteractions(sender, claimInvitations);
  }

  @Test
  void testRelationship_UpdateStage_WithAgreedFee() throws Exception {
    UUID relationship =
        UUID.fromString(
            call(
                    brand,
                    post("/campaigns/" + campaign + "/relationships"),
                    Map.of("creatorId", c.getId()),
                    200)
                .path("data")
                .path("id")
                .asText());
    call(
        brand,
        patch("/campaigns/" + campaign + "/relationships/" + relationship + "/stage"),
        Map.of("stage", "CONFIRMED", "agreedFee", 100000),
        400);
    assertThat(
            jdbc.queryForObject(
                "select stage from relationship where id=?", String.class, relationship))
        .isEqualTo("CONTACTED");
    assertThat(
            jdbc.queryForObject(
                "select agreed_fee from relationship where id=?",
                java.math.BigDecimal.class,
                relationship))
        .isNull();
    call(
        brand,
        patch("/campaigns/" + campaign + "/relationships/" + relationship + "/stage"),
        Map.of("stage", "NEGOTIATING", "nextAction", "Discuss proposal"),
        200);
    assertThat(
            jdbc.queryForObject(
                "select next_action from relationship where id=?", String.class, relationship))
        .isEqualTo("Discuss proposal");
  }

  @Test
  void testF409NewestBookingEmailIsUsedWithoutClaiming() throws Exception {
    metrics.saveAndFlush(
        PublicCreatorMetric.builder()
            .creator(c)
            .collectedAt(LocalDateTime.now().minusDays(1))
            .contact("old@example.test")
            .freshnessStatus(com.influencermatch.backend.creator.enums.MetricDataStatus.FRESH)
            .build());
    metrics.saveAndFlush(
        PublicCreatorMetric.builder()
            .creator(c)
            .collectedAt(LocalDateTime.now())
            .contact("new@example.test")
            .freshnessStatus(com.influencermatch.backend.creator.enums.MetricDataStatus.FRESH)
            .build());
    var result = outreach();
    String payload =
        jdbc.queryForObject(
            "select payload from cooperation_email_job where invitation_id=?",
            String.class,
            UUID.fromString(result.path("invitationId").asText()));
    var cipher =
        new com.influencermatch.backend.creator.email.ClaimEmailCipher(
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", json);
    assertThat(cipher.decrypt(payload).email()).isEqualTo("new@example.test");
    assertThat(result.path("contactStatus").asText()).isEqualTo("QUEUED");
    verifyNoInteractions(claimInvitations);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from collaboration where campaign_id=?", Long.class, campaign))
        .isZero();
  }

  @Test
  void testF410CrmNegotiationCannotWriteAgreedFeeOrConfirmByDragging() throws Exception {
    UUID r =
        UUID.fromString(
            call(
                    brand,
                    post("/campaigns/" + campaign + "/relationships"),
                    Map.of("creatorId", c.getId(), "offeredFee", 100000, "currency", "VND"),
                    200)
                .path("data")
                .path("id")
                .asText());
    call(
        brand,
        patch("/campaigns/" + campaign + "/relationships/" + r + "/stage"),
        Map.of("stage", "NEGOTIATING", "nextAction", "Discuss deliverables"),
        200);
    call(
        brand,
        patch("/campaigns/" + campaign + "/relationships/" + r + "/stage"),
        Map.of("stage", "CONFIRMED"),
        409);
    call(
        brand,
        patch("/campaigns/" + campaign + "/relationships/" + r + "/details"),
        Map.of("agreedFee", 100000),
        400);
    assertThat(
            jdbc.queryForObject(
                "select offered_fee from relationship where id=?", java.math.BigDecimal.class, r))
        .isEqualByComparingTo("100000");
    assertThat(
            jdbc.queryForObject(
                "select agreed_fee from relationship where id=?", java.math.BigDecimal.class, r))
        .isNull();
    assertThat(
            jdbc.queryForObject("select next_action from relationship where id=?", String.class, r))
        .isEqualTo("Discuss deliverables");
  }
}
