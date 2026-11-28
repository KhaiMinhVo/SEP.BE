package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.service.CampaignWriteLock;
import com.influencermatch.backend.creator.portal.CreatorPortalProjection;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class DealServiceUnitTest {

  private JdbcTemplate db;
  private DealStore store;
  private CampaignWriteLock locks;
  private AuditService audit;
  private ExecutionSnapshot executionSnapshot;
  private CooperationDelivery delivery;
  private ExecutionHistoryReader historyReader;
  private CreatorPortalProjection creatorProjection;
  private RecommendationContactPolicy contactPolicy;
  private DealService service;

  private final Instant fixedNow = Instant.parse("2026-11-26T10:00:00Z");
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  private User brandUser;
  private User creatorUser;
  private UUID campaignId;
  private UUID dealId;
  private UUID masterId;
  private UUID creatorId;
  private UUID relationshipId;
  private UUID proposalId;
  private UUID collaborationId;

  @BeforeEach
  void setUp() {
    db = mock(JdbcTemplate.class);
    store = new DealStore(db, json, Clock.fixed(fixedNow, ZoneOffset.UTC));
    locks = mock(CampaignWriteLock.class);
    audit = mock(AuditService.class);
    executionSnapshot = mock(ExecutionSnapshot.class);
    delivery = mock(CooperationDelivery.class);
    historyReader = mock(ExecutionHistoryReader.class);
    creatorProjection = mock(CreatorPortalProjection.class);
    contactPolicy = mock(RecommendationContactPolicy.class);

    service = new DealService(
        store, locks, audit, executionSnapshot, delivery, historyReader, creatorProjection);
    ReflectionTestUtils.setField(service, "contactPolicy", contactPolicy);
    ReflectionTestUtils.setField(service, "em", mock(EntityManager.class));

    brandUser = TestActors.user(Role.BRAND);
    brandUser.setEmailVerified(true);
    brandUser.setAuthVersion(1L);
    creatorUser = TestActors.user(Role.CREATOR);
    creatorUser.setEmailVerified(true);
    creatorUser.setAuthVersion(1L);

    campaignId = UUID.randomUUID();
    dealId = UUID.randomUUID();
    masterId = UUID.randomUUID();
    creatorId = UUID.randomUUID();
    relationshipId = UUID.randomUUID();
    proposalId = UUID.randomUUID();
    collaborationId = UUID.randomUUID();

    lenient().when(delivery.plannedContactChannel(any())).thenReturn("IN_APP_PORTAL");
    lenient().when(delivery.availableDeliveryMethods(any())).thenReturn(List.of("PORTAL_ONLY"));
    lenient().when(locks.owned(campaignId)).thenReturn(
        Campaign.builder().id(campaignId).status(CampaignStatus.ACTIVE).build());
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private Map<String, Object> dealRow(String status, long rev, Long acceptedRev, UUID collabId) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", dealId);
    row.put("campaign_id", campaignId);
    row.put("brand_user_id", brandUser.getId());
    row.put("master_id", masterId);
    row.put("recipient_key", "MASTER:" + masterId);
    row.put("status", status);
    row.put("revision", rev);
    row.put("source", "PORTAL");
    row.put("accepted_revision", acceptedRev);
    row.put("accepted_by", acceptedRev == null ? null : creatorUser.getId());
    row.put("collaboration_id", collabId);
    row.put("created_at", Timestamp.from(fixedNow.minusSeconds(3600)));
    row.put("updated_at", Timestamp.from(fixedNow.minusSeconds(60)));
    return row;
  }

  private Map<String, Object> proposalRow(long rev, String kind, BigDecimal fee) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("id", proposalId);
    p.put("deal_id", dealId);
    p.put("revision", rev);
    p.put("kind", kind);
    p.put("fee", fee);
    p.put("currency", "VND");
    p.put("terms", "Standard terms");
    p.put("deliverables", "[]");
    p.put("kpi_type", "VIEWS");
    p.put("kpi_target", 10000L);
    p.put("created_at", Timestamp.from(fixedNow.minusSeconds(600)));
    return p;
  }

  private Map<String, Object> memberRow() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("deal_id", dealId);
    m.put("relationship_id", relationshipId);
    m.put("creator_id", creatorId);
    m.put("master_id", masterId);
    m.put("platform", "TIKTOK");
    m.put("source_type", "AUTHORIZED");
    m.put("user_name", "koc_tiktok");
    return m;
  }

  private void stubCommonDealQueries(
      Map<String, Object> deal,
      Map<String, Object> proposal,
      String campaignStatus,
      BigDecimal totalBudget,
      String creatorMode,
      List<Map<String, Object>> existingCollabs,
      String collaborationStatus,
      long deliverablesCount) {

    when(db.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      if (sql.contains("from \"user\" where id=?")) {
        User current = com.influencermatch.backend.security.permission.Permissions.actor();
        return List.of(Map.of(
            "role", current.getRole().name(),
            "status", "ACTIVE",
            "email_verified", true,
            "auth_version", current.getAuthVersion()));
      }
      if (sql.contains("from cooperation_deal where id=?")) {
        return List.of(deal);
      }
      if (sql.contains("from cooperation_member m join creator c")) {
        return List.of(memberRow());
      }
      if (sql.contains("select relationship_id from cooperation_member")) {
        return List.of(Map.of("relationship_id", relationshipId));
      }
      if (sql.contains("select id from creator where id=?") || sql.contains("select id from relationship where id=?")) {
        return List.of(Map.of("id", inv.getArgument(1)));
      }
      if (sql.contains("from cooperation_idempotency")) {
        return List.of();
      }
      if (sql.contains("from creator_master m join \"user\" u")) {
        return List.of(Map.of("id", masterId));
      }
      if (sql.contains("from campaign where id=?")) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", campaignId);
        c.put("status", campaignStatus);
        c.put("total_budget", totalBudget);
        c.put("currency", "VND");
        c.put("creator_mode", creatorMode);
        c.put("target_creator_count", 1);
        return List.of(c);
      }
      if (sql.contains("from campaign c join brand_profile b")) {
        return List.of(Map.of("name", "Summer", "status", campaignStatus, "business_name", "Acme"));
      }
      if (sql.contains("from cooperation_proposal where deal_id=?")) {
        return proposal == null ? List.of() : List.of(proposal);
      }
      if (sql.contains("select total_budget,currency from campaign")) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("total_budget", totalBudget);
        c.put("currency", "VND");
        return List.of(c);
      }
      if (sql.contains("select agreed_fee,currency from collaboration")) {
        return existingCollabs;
      }
      if (sql.contains("select distinct case when")) {
        List<Map<String, Object>> recipients = new ArrayList<>();
        for (var col : existingCollabs) {
          if (col.containsKey("recipient")) {
            recipients.add(Map.of("recipient", col.get("recipient")));
          }
        }
        return recipients;
      }
      if (sql.contains("select status,management_mode from collaboration")
          || sql.contains("select status from collaboration")) {
        return List.of(Map.of("status", collaborationStatus, "management_mode", "BRAND_MANAGED"));
      }
      if (sql.contains("select agreed_fee from collaboration")) {
        return List.of(Map.of("agreed_fee", proposal != null ? proposal.get("fee") : BigDecimal.ZERO));
      }
      if (sql.contains("from creator_master where id=? and user_id=?")) {
        return List.of(Map.of("id", masterId));
      }
      if (sql.contains("from rbac_guard")) {
        return List.of(Map.of("id", 1));
      }
      return List.of();
    });

    when(db.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      if (sql.contains("collaboration_deliverable")) {
        return deliverablesCount;
      }
      return 0L;
    });
    when(db.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class))).thenReturn(BigDecimal.ZERO);
  }

  // ===================== confirmDeal (6 UTCIDs) =====================

  @Test
  @DisplayName("confirmDeal UTCID01 [N]: ACTIVE campaign with accepted portal proposal confirms deal and creates PLANNED collaboration")
  void confirmDeal_validPortalProposalConfirmsAndCreatesCollaboration() {
    TestActors.authenticate(brandUser);
    Map<String, Object> deal = dealRow("NEGOTIATING", 2L, 2L, null);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 1L);

    when(db.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      if (sql.startsWith("update cooperation_deal set status='CONFIRMED'")) {
        deal.put("status", "CONFIRMED");
        deal.put("revision", 3L);
        deal.put("collaboration_id", inv.getArgument(1));
      }
      return 1;
    });

    JsonNode res = service.confirm(
        campaignId, dealId, new CooperationDtos.Confirm(2L, "PORTAL", null, null, null, null), "confirm-key-1");

    assertThat(res.get("status").asText()).isEqualTo("CONFIRMED");
    assertThat(res.get("revision").asLong()).isEqualTo(3L);
    verify(executionSnapshot).create(any(), eq(proposalId), any(), eq("PROPOSAL"));
    verify(db).update(startsWith("insert into collaboration("), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("confirmDeal UTCID02 [B]: Committed budget plus fee equals total budget succeeds")
  void confirmDeal_budgetExactlyExhaustedSucceeds() {
    TestActors.authenticate(brandUser);
    Map<String, Object> deal = dealRow("NEGOTIATING", 2L, 2L, null);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("4000000"));
    Map<String, Object> committed = Map.of("agreed_fee", new BigDecimal("6000000"), "currency", "VND");
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "MULTI", List.of(committed), "PLANNED", 1L);

    when(db.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      if (sql.startsWith("update cooperation_deal set status='CONFIRMED'")) {
        deal.put("status", "CONFIRMED");
        deal.put("revision", 3L);
      }
      return 1;
    });

    JsonNode res = service.confirm(
        campaignId, dealId, new CooperationDtos.Confirm(2L, "PORTAL", null, null, null, null), "confirm-key-2");

    assertThat(res.get("status").asText()).isEqualTo("CONFIRMED");
    verify(executionSnapshot).create(any(), eq(proposalId), any(), eq("PROPOSAL"));
  }

  @Test
  @DisplayName("confirmDeal UTCID03 [A]: Committed budget plus fee exceeds total budget throws DEAL_BUDGET_EXCEEDED")
  void confirmDeal_budgetExceededThrows() {
    TestActors.authenticate(brandUser);
    Map<String, Object> deal = dealRow("NEGOTIATING", 2L, 2L, null);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("4000001"));
    Map<String, Object> committed = Map.of("agreed_fee", new BigDecimal("6000000"), "currency", "VND");
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "MULTI", List.of(committed), "PLANNED", 1L);

    assertThatThrownBy(() -> service.confirm(
        campaignId, dealId, new CooperationDtos.Confirm(2L, "PORTAL", null, null, null, null), "confirm-key-3"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.DEAL_BUDGET_EXCEEDED));
    verifyNoInteractions(executionSnapshot);
  }

  @Test
  @DisplayName("confirmDeal UTCID04 [A]: Portal acceptance revision differs from current proposal throws conflict")
  void confirmDeal_staleAcceptanceRevisionThrowsConflict() {
    TestActors.authenticate(brandUser);
    Map<String, Object> deal = dealRow("NEGOTIATING", 3L, 2L, null);
    Map<String, Object> proposal = proposalRow(3L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 1L);

    assertThatThrownBy(() -> service.confirm(
        campaignId, dealId, new CooperationDtos.Confirm(3L, "PORTAL", null, null, null, null), "confirm-key-4"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.DEAL_STATE_CONFLICT));
    verifyNoInteractions(executionSnapshot);
  }

  @Test
  @DisplayName("confirmDeal UTCID05 [A]: External agreement evidence URL using HTTP throws ValidationException")
  void confirmDeal_externalHttpEvidenceUrlThrowsValidation() {
    TestActors.authenticate(brandUser);
    Map<String, Object> deal = dealRow("NEGOTIATING", 2L, null, null);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 1L);

    assertThatThrownBy(() -> service.confirm(
        campaignId,
        dealId,
        new CooperationDtos.Confirm(
            2L, "EXTERNAL", fixedNow.minusSeconds(60), "Zalo", "http://insecure.example/proof", "Confirmed via chat"),
        "confirm-key-5"))
        .isInstanceOf(ValidationException.class);
    verifyNoInteractions(executionSnapshot);
  }

  @Test
  @DisplayName("confirmDeal UTCID06 [A]: SINGLE campaign with existing different recipient throws DEAL_SINGLE_LIMIT")
  void confirmDeal_singleCampaignDuplicateRecipientThrows() {
    TestActors.authenticate(brandUser);
    Map<String, Object> deal = dealRow("NEGOTIATING", 2L, 2L, null);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("2000000"));
    Map<String, Object> otherCollab = new LinkedHashMap<>();
    otherCollab.put("agreed_fee", new BigDecimal("1000000"));
    otherCollab.put("currency", "VND");
    otherCollab.put("recipient", "MASTER:" + UUID.randomUUID());
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(otherCollab), "PLANNED", 1L);

    assertThatThrownBy(() -> service.confirm(
        campaignId, dealId, new CooperationDtos.Confirm(2L, "PORTAL", null, null, null, null), "confirm-key-6"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.DEAL_SINGLE_LIMIT));
    verifyNoInteractions(executionSnapshot);
  }

  // ===================== respondToDeal (5 UTCIDs) =====================

  @Test
  @DisplayName("respondToDeal UTCID01 [N]: Accept current OFFER with matching revision records acceptance and event")
  void respondToDeal_acceptOfferRecordsAcceptanceAndEvent() {
    TestActors.authenticate(creatorUser);
    Map<String, Object> deal = dealRow("NEGOTIATING", 2L, null, null);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 1L);

    when(db.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      if (sql.startsWith("update cooperation_deal set accepted_revision=?")) {
        deal.put("accepted_revision", inv.getArgument(1));
        deal.put("accepted_by", inv.getArgument(2));
      }
      return 1;
    });

    @SuppressWarnings("unchecked")
    Map<String, Object> res = (Map<String, Object>) service.respond(
        dealId, "accept", new CooperationDtos.Revision(2L));

    assertThat(res.get("acceptedRevision")).isEqualTo(2L);
    verify(db).update(startsWith("update cooperation_deal set accepted_revision=?"),
        eq(2L), eq(creatorUser.getId()), any(), any(), eq(dealId));
    verify(audit).record(eq("PROPOSAL_ACCEPTED"), eq("COOPERATION_DEAL"), eq(dealId), isNull(), any(), anyString());
  }

  @Test
  @DisplayName("respondToDeal UTCID02 [N]: Decline current proposal sets DECLINED, increments revision, invalidates invitations")
  void respondToDeal_declineMarksDeclinedAndIncrementsRevision() {
    TestActors.authenticate(creatorUser);
    Map<String, Object> deal = dealRow("NEGOTIATING", 2L, null, null);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 1L);

    when(db.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      if (sql.startsWith("update cooperation_deal set status='DECLINED'")) {
        deal.put("status", "DECLINED");
        deal.put("revision", 3L);
      }
      return 1;
    });

    @SuppressWarnings("unchecked")
    Map<String, Object> res = (Map<String, Object>) service.respond(
        dealId, "decline", new CooperationDtos.Revision(2L));

    assertThat(res.get("status")).isEqualTo("DECLINED");
    assertThat(res.get("revision")).isEqualTo(3L);
    verify(db).update(startsWith("update cooperation_invitation set status='REPLACED'"), eq(dealId));
    verify(audit).record(eq("DEAL_DECLINED"), eq("COOPERATION_DEAL"), eq(dealId), isNull(), any(), anyString());
  }

  @Test
  @DisplayName("respondToDeal UTCID03 [N]: Repeated acceptance on already accepted offer returns view without update or event")
  void respondToDeal_alreadyAcceptedReturnsExistingViewWithoutUpdate() {
    TestActors.authenticate(creatorUser);
    Map<String, Object> deal = dealRow("NEGOTIATING", 2L, 2L, null);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 1L);

    @SuppressWarnings("unchecked")
    Map<String, Object> res = (Map<String, Object>) service.respond(
        dealId, "accept", new CooperationDtos.Revision(2L));

    assertThat(res.get("acceptedRevision")).isEqualTo(2L);
    verify(db, never()).update(startsWith("update cooperation_deal set accepted_revision"), any(Object[].class));
    verifyNoInteractions(audit);
  }

  @Test
  @DisplayName("respondToDeal UTCID04 [A]: Accepting a COUNTEROFFER throws conflict")
  void respondToDeal_acceptCounterofferThrowsConflict() {
    TestActors.authenticate(creatorUser);
    Map<String, Object> deal = dealRow("NEGOTIATING", 3L, null, null);
    Map<String, Object> proposal = proposalRow(3L, "COUNTEROFFER", new BigDecimal("6000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 1L);

    assertThatThrownBy(() -> service.respond(dealId, "accept", new CooperationDtos.Revision(3L)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.DEAL_STATE_CONFLICT));
    verify(db, never()).update(startsWith("update cooperation_deal set accepted_revision"), any(Object[].class));
  }

  @Test
  @DisplayName("respondToDeal UTCID05 [A]: Stale expected revision throws DEAL_REVISION_CONFLICT")
  void respondToDeal_staleRevisionThrows() {
    TestActors.authenticate(creatorUser);
    Map<String, Object> deal = dealRow("NEGOTIATING", 4L, null, null);
    Map<String, Object> proposal = proposalRow(4L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 1L);

    assertThatThrownBy(() -> service.respond(dealId, "accept", new CooperationDtos.Revision(2L)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.DEAL_REVISION_CONFLICT));
    verifyNoInteractions(audit);
  }

  // ===================== transitionDeal (6 UTCIDs) =====================

  @Test
  @DisplayName("transitionDeal UTCID01 [N]: Start CONFIRMED deal on ACTIVE campaign transitions collaboration to IN_PROGRESS")
  void transitionDeal_startValidMovesToInProgressAndIncrementsRevision() {
    TestActors.authenticate(brandUser);
    Map<String, Object> deal = dealRow("CONFIRMED", 3L, 2L, collaborationId);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 2L);

    when(db.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      if (sql.startsWith("update cooperation_deal set revision=revision+1")) {
        deal.put("revision", 4L);
      }
      return 1;
    });

    @SuppressWarnings("unchecked")
    Map<String, Object> res = (Map<String, Object>) service.transition(
        campaignId, dealId, "start", new CooperationDtos.Cancel(3L, "Begin campaign"));

    assertThat(res.get("revision")).isEqualTo(4L);
    verify(db).update(startsWith("update collaboration set status='IN_PROGRESS'"), any(), any(), eq(collaborationId));
    verify(audit).record(eq("DEAL_START"), eq("COOPERATION_DEAL"), eq(dealId), isNull(), any(), anyString());
  }

  @Test
  @DisplayName("transitionDeal UTCID02 [B]: Start with exact boundary 1 deliverable succeeds")
  void transitionDeal_startWithSingleDeliverableSucceeds() {
    TestActors.authenticate(brandUser);
    Map<String, Object> deal = dealRow("CONFIRMED", 3L, 2L, collaborationId);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 1L);

    when(db.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      if (sql.startsWith("update cooperation_deal set revision=revision+1")) {
        deal.put("revision", 4L);
      }
      return 1;
    });

    @SuppressWarnings("unchecked")
    Map<String, Object> res = (Map<String, Object>) service.transition(
        campaignId, dealId, "start", new CooperationDtos.Cancel(3L, "One deliverable ready"));

    assertThat(res.get("revision")).isEqualTo(4L);
    verify(db).update(startsWith("update collaboration set status='IN_PROGRESS'"), any(), any(), eq(collaborationId));
  }

  @Test
  @DisplayName("transitionDeal UTCID03 [A]: Start with 0 deliverables throws conflict and keeps collaboration PLANNED")
  void transitionDeal_startZeroDeliverablesRejected() {
    TestActors.authenticate(brandUser);
    Map<String, Object> deal = dealRow("CONFIRMED", 3L, 2L, collaborationId);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 0L);

    assertThatThrownBy(() -> service.transition(
        campaignId, dealId, "start", new CooperationDtos.Cancel(3L, "No deliverables")))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.DEAL_STATE_CONFLICT));
    verify(db, never()).update(startsWith("update collaboration set status='IN_PROGRESS'"), any(Object[].class));
  }

  @Test
  @DisplayName("transitionDeal UTCID04 [A]: Start when campaign is not ACTIVE throws conflict")
  void transitionDeal_startNonActiveCampaignRejected() {
    TestActors.authenticate(brandUser);
    Map<String, Object> deal = dealRow("CONFIRMED", 3L, 2L, collaborationId);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "READY_FOR_DISCOVERY", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 1L);

    assertThatThrownBy(() -> service.transition(
        campaignId, dealId, "start", new CooperationDtos.Cancel(3L, "Not active")))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.DEAL_STATE_CONFLICT));
    verify(db, never()).update(startsWith("update collaboration set status='IN_PROGRESS'"), any(Object[].class));
  }

  @Test
  @DisplayName("transitionDeal UTCID05 [N]: Cancel deal with PLANNED collaboration cancels both and deactivates members")
  void transitionDeal_cancelPlannedCancelsDealAndCollaboration() {
    TestActors.authenticate(brandUser);
    Map<String, Object> deal = dealRow("CONFIRMED", 3L, 2L, collaborationId);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "PLANNED", 1L);

    when(db.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      if (sql.startsWith("update cooperation_deal set status='CANCELLED'")) {
        deal.put("status", "CANCELLED");
      }
      if (sql.startsWith("update cooperation_deal set revision=revision+1")) {
        deal.put("revision", 4L);
      }
      return 1;
    });

    @SuppressWarnings("unchecked")
    Map<String, Object> res = (Map<String, Object>) service.transition(
        campaignId, dealId, "cancel", new CooperationDtos.Cancel(3L, "Brand cancelled"));

    assertThat(res.get("status")).isEqualTo("CANCELLED");
    assertThat(res.get("revision")).isEqualTo(4L);
    verify(db).update(startsWith("update collaboration set status='CANCELLED'"), any(), eq(collaborationId));
    verify(db).update(startsWith("update cooperation_member set active=false"), eq(dealId));
  }

  @Test
  @DisplayName("transitionDeal UTCID06 [A]: Cancel deal with IN_PROGRESS collaboration throws conflict")
  void transitionDeal_cancelInProgressRejected() {
    TestActors.authenticate(brandUser);
    Map<String, Object> deal = dealRow("CONFIRMED", 3L, 2L, collaborationId);
    Map<String, Object> proposal = proposalRow(2L, "OFFER", new BigDecimal("5000000"));
    stubCommonDealQueries(deal, proposal, "ACTIVE", new BigDecimal("10000000"), "SINGLE", List.of(), "IN_PROGRESS", 1L);

    assertThatThrownBy(() -> service.transition(
        campaignId, dealId, "cancel", new CooperationDtos.Cancel(3L, "Too late")))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.DEAL_STATE_CONFLICT));
    verify(db, never()).update(startsWith("update cooperation_deal set status='CANCELLED'"), any(Object[].class));
  }
}
