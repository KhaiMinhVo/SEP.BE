package com.influencermatch.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.billing.service.SubscriptionService;
import com.influencermatch.backend.brand.model.BrandContext;
import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandContextRepository;
import com.influencermatch.backend.brand.repository.BrandPatternRepository;
import com.influencermatch.backend.campaign.enums.CampaignObjective;
import com.influencermatch.backend.campaign.enums.CandidateSource;
import com.influencermatch.backend.campaign.history.HistoricalEvidenceService;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.CampaignContext;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.campaign.model.RecommendationRun;
import com.influencermatch.backend.campaign.repository.CampaignContextRepository;
import com.influencermatch.backend.campaign.repository.HistoricalCampaignRepository;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.campaign.service.CampaignM3ContextService;
import com.influencermatch.backend.collaboration.model.CampaignOutcome;
import com.influencermatch.backend.collaboration.model.Collaboration;
import com.influencermatch.backend.creator.client.AiServiceClient;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.creator.repository.PostRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.recommendation.dto.CreatorRecommendationItemDto;
import com.influencermatch.backend.recommendation.dto.RecommendationResponseDto;
import com.influencermatch.backend.recommendation.dto.ScoreBreakdownDto;
import com.influencermatch.backend.recommendation.engine.memory.LearnedM4Evaluator;
import com.influencermatch.backend.recommendation.engine.memory.M1HistoricalScorer;
import com.influencermatch.backend.recommendation.engine.presentation.enums.DisplayMode;
import com.influencermatch.backend.recommendation.service.AiRecommendationService;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.support.TestM4Fixtures;
import com.influencermatch.backend.user.enums.Role;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * End-to-End Verification Test Suite for Post-Campaign Learning Backend
 * (SP-01).
 * Covers:
 * Phase 1: Append-Only Historical Evidence (Create, Amend, Revoke, 24m cutoff,
 * As-Of resolution)
 * Phase 2: M1 Historical Scoring (R/T/K Architecture, Objective weights,
 * Minimum Evidence Gate)
 * Phase 3: Learned M4 Evaluator (4-Tier Evidence Hierarchy, Cohort Aggregation,
 * Explicit Fallback)
 * Phase 4: Recommendation Pool Policy & Gate Integration (Main Pool vs
 * Provisional Pool)
 * Phase 5: Immutable Scoring Snapshot & Audit Trail (Run Metadata, Structured
 * Snapshot)
 */
class PostCampaignLearningIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final M1HistoricalScorer m1Scorer = new M1HistoricalScorer();
  private final LearnedM4Evaluator m4Evaluator = new LearnedM4Evaluator();

  @Nested
  @DisplayName("Phase 1: Append-Only Historical Evidence Tests")
  class AppendOnlyEvidenceTests {

    private HistoricalCampaignRepository repository;
    private HistoricalEvidenceService evidenceService;

    @BeforeEach
    void setUp() {
      repository = mock(HistoricalCampaignRepository.class);
      evidenceService = new HistoricalEvidenceService(repository);
    }

    @Test
    @DisplayName("Create evidence generates revision 1 with ACTIVE status")
    void testCreateEvidence_InitialRevision() {
      when(repository.save(any(HistoricalCampaign.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      UUID creatorId = UUID.randomUUID();
      Creator creator = Creator.builder().id(creatorId).build();
      LocalDateTime businessTime = LocalDateTime.now().minusDays(5);

      ObjectNode proof = objectMapper.createObjectNode()
          .put("overallRating", 5)
          .put("brandFitRating", 4)
          .put("allDeliverablesOnTime", true);

      HistoricalCampaign created = evidenceService.createEvidence(
          creator, null, null, null,
          "COLLABORATION_REVIEW", proof, "Great collab", "SELF_REPORTED",
          UUID.randomUUID(), 1L, "BRAND_ACCEPTED", businessTime);

      assertThat(created).isNotNull();
      assertThat(created.getEvidenceRootId()).isNotNull();
      assertThat(created.getRevisionNumber()).isEqualTo(1L);
      assertThat(created.getStatus()).isEqualTo("ACTIVE");
      assertThat(created.getSchemaVersion()).isEqualTo("1.0");
      assertThat(created.getBusinessOccurredAt()).isEqualTo(businessTime);
    }

    @Test
    @DisplayName("Amend evidence appends new revision and points supersedesId to previous")
    void testAmendEvidence_AppendsRevision() {
      UUID rootId = UUID.randomUUID();
      UUID rev1Id = UUID.randomUUID();

      HistoricalCampaign rev1 = HistoricalCampaign.builder()
          .id(rev1Id)
          .evidenceRootId(rootId)
          .revisionNumber(1L)
          .status("ACTIVE")
          .schemaVersion("1.0")
          .recordedAt(LocalDateTime.now().minusDays(10))
          .build();

      when(repository.findByEvidenceRootIdOrderByRevisionNumberDesc(rootId))
          .thenReturn(List.of(rev1));
      when(repository.save(any(HistoricalCampaign.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      ObjectNode updatedProof = objectMapper.createObjectNode().put("overallRating", 4);
      HistoricalCampaign amended = evidenceService.amendEvidence(
          rootId, updatedProof, "Amended note", "VERIFIED", LocalDateTime.now());

      assertThat(amended.getEvidenceRootId()).isEqualTo(rootId);
      assertThat(amended.getRevisionNumber()).isEqualTo(2L);
      assertThat(amended.getSupersedesId()).isEqualTo(rev1Id);
      assertThat(amended.getStatus()).isEqualTo("ACTIVE");
      assertThat(amended.getQuality()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("Revoke evidence creates terminal REVOKED revision")
    void testRevokeEvidence_AppendsRevokedRevision() {
      UUID rootId = UUID.randomUUID();
      UUID rev2Id = UUID.randomUUID();

      HistoricalCampaign rev2 = HistoricalCampaign.builder()
          .id(rev2Id)
          .evidenceRootId(rootId)
          .revisionNumber(2L)
          .status("ACTIVE")
          .schemaVersion("1.0")
          .recordedAt(LocalDateTime.now().minusDays(5))
          .build();

      when(repository.findByEvidenceRootIdOrderByRevisionNumberDesc(rootId))
          .thenReturn(List.of(rev2));
      when(repository.save(any(HistoricalCampaign.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      HistoricalCampaign revoked = evidenceService.revokeEvidence(rootId, "Revoked due to review fraud");

      assertThat(revoked.getEvidenceRootId()).isEqualTo(rootId);
      assertThat(revoked.getRevisionNumber()).isEqualTo(3L);
      assertThat(revoked.getStatus()).isEqualTo("REVOKED");
      assertThat(revoked.getSupersedesId()).isEqualTo(rev2Id);
    }

    @Test
    @DisplayName("As-Of point in time resolution excludes revoked evidence and honors 24-month window")
    void testSelectValidEvidenceAt_PointInTimeAndWindow() {
      UUID creatorId = UUID.randomUUID();
      UUID rootActive = UUID.randomUUID();
      UUID rootRevoked = UUID.randomUUID();
      UUID rootExpired = UUID.randomUUID();

      LocalDateTime now = LocalDateTime.now();

      // 1. Active evidence within 24 months
      HistoricalCampaign activeEntry = HistoricalCampaign.builder()
          .id(UUID.randomUUID())
          .evidenceRootId(rootActive)
          .revisionNumber(1L)
          .status("ACTIVE")
          .recordedAt(now.minusMonths(6))
          .build();

      // 2. Revoked evidence (rev 1 active at -12m, rev 2 revoked at -2m)
      HistoricalCampaign revokedRev1 = HistoricalCampaign.builder()
          .id(UUID.randomUUID())
          .evidenceRootId(rootRevoked)
          .revisionNumber(1L)
          .status("ACTIVE")
          .recordedAt(now.minusMonths(12))
          .build();
      HistoricalCampaign revokedRev2 = HistoricalCampaign.builder()
          .id(UUID.randomUUID())
          .evidenceRootId(rootRevoked)
          .revisionNumber(2L)
          .status("REVOKED")
          .recordedAt(now.minusMonths(2))
          .build();

      // 3. Expired evidence (> 24 months old as of both now and fourMonthsAgo)
      HistoricalCampaign expiredEntry = HistoricalCampaign.builder()
          .id(UUID.randomUUID())
          .evidenceRootId(rootExpired)
          .revisionNumber(1L)
          .status("ACTIVE")
          .recordedAt(now.minusMonths(30))
          .build();

      when(repository.findByCreatorId(creatorId))
          .thenReturn(List.of(activeEntry, revokedRev1, revokedRev2, expiredEntry));

      // Query as of NOW: activeEntry included; revokedEntry is REVOKED -> excluded;
      // expiredEntry > 24m -> excluded
      List<HistoricalCampaign> resultsNow = evidenceService.selectValidEvidenceAt(creatorId, null, now);
      assertThat(resultsNow).hasSize(1);
      assertThat(resultsNow.get(0).getEvidenceRootId()).isEqualTo(rootActive);

      // Query as of 4 MONTHS AGO (before revocation happened): revokedRev1 WAS ACTIVE
      // -> included!
      LocalDateTime fourMonthsAgo = now.minusMonths(4);
      List<HistoricalCampaign> resultsPast = evidenceService.selectValidEvidenceAt(creatorId, null, fourMonthsAgo);
      assertThat(resultsPast).hasSize(2);
      assertThat(resultsPast).extracting(HistoricalCampaign::getEvidenceRootId)
          .containsExactlyInAnyOrder(rootActive, rootRevoked);
    }

    @Test
    @DisplayName("AMEND today of an old collaboration (> 24 months old) does NOT extend the 24-month window")
    void testAmend_DoesNotExtend24MonthWindow() {
      UUID creatorId = UUID.randomUUID();
      UUID rootId = UUID.randomUUID();
      LocalDateTime now = LocalDateTime.now();

      // Original collaboration occurred 26 months ago
      HistoricalCampaign rev1 = HistoricalCampaign.builder()
          .id(UUID.randomUUID())
          .evidenceRootId(rootId)
          .revisionNumber(1L)
          .status("ACTIVE")
          .businessOccurredAt(now.minusMonths(26))
          .recordedAt(now.minusMonths(26))
          .build();

      // Amended TODAY: recordedAt = now, but businessOccurredAt remains 26 months
      // ago!
      HistoricalCampaign rev2 = HistoricalCampaign.builder()
          .id(UUID.randomUUID())
          .evidenceRootId(rootId)
          .revisionNumber(2L)
          .status("ACTIVE")
          .businessOccurredAt(now.minusMonths(26))
          .recordedAt(now)
          .build();

      when(repository.findByCreatorId(creatorId)).thenReturn(List.of(rev1, rev2));

      // As of NOW: Even though rev2 was recorded today, businessOccurredAt is 26
      // months ago (> 24m) -> MUST BE EXCLUDED!
      List<HistoricalCampaign> valid = evidenceService.selectValidEvidenceAt(creatorId, null, now);
      assertThat(valid).isEmpty();
    }
  }

  @Nested
  @DisplayName("Phase 2: M1 R/T/K Scorer & Minimum Evidence Gate Tests")
  class M1ScorerTests {

    private HistoricalCampaign buildObservation(
        UUID brandId, ObjectNode proof, String quality, LocalDateTime time) {
      var campaign = Campaign.builder().id(UUID.randomUUID())
          .brandProfile(brandId == null ? null : BrandProfile.builder().id(brandId).build()).build();
      Collaboration collab = Collaboration.builder().id(UUID.randomUUID()).campaign(campaign).build();
      if (brandId != null) proof.put("brandProfileId", brandId.toString());
      return HistoricalCampaign.builder()
          .id(UUID.randomUUID())
          .evidenceRootId(UUID.randomUUID())
          .revisionNumber(1L)
          .status("ACTIVE")
          .evidenceType("COLLABORATION_COMPLETION")
          .collaboration(collab)
          .evidenceValue(proof)
          .quality(quality)
          .recordedAt(time)
          .businessOccurredAt(time)
          .build();
    }

    private HistoricalCampaign buildCollab(UUID brandId, int rating, boolean onTime,
        double kpiAttainment, String quality, LocalDateTime time) {
      ObjectNode proof = objectMapper.createObjectNode()
          .put("evidenceSchemaVersion", "collaboration-completion-evidence-v1")
          .put("brandAccepted", true)
          .put("completionMethod", "WITH_REVIEW")
          .put("managementMode", "COLLABORATIVE")
          .put("outcomeRevision", 1)
          .put("overallRating", rating)
          .put("brandFitRating", rating)
          .put("wouldCollaborateAgain", rating >= 4)
          .put("kpiStatus", "AVAILABLE")
          .put("kpiType", "VIEWS")
          .put("kpiTarget", 1000)
          .put("kpiActual", Math.round(kpiAttainment * 1000))
          .put("kpiEvidenceQuality", quality);
      proof.putArray("deliverables").addObject()
          .put("submissionSource", "CREATOR_PORTAL")
          .put("submittedAt", onTime ? time.minusMinutes(1).toString() : time.plusMinutes(1).toString())
          .put("dueAt", time.toString());
      return buildObservation(brandId, proof, quality, time);
    }

    private HistoricalCampaign withoutReview(UUID brandId, LocalDateTime time) {
      var source = buildCollab(brandId, 5, true, 1.0, "VERIFIED", time);
      var proof = ((ObjectNode) source.getEvidenceValue()).deepCopy();
      proof.remove(List.of("overallRating", "brandFitRating", "wouldCollaborateAgain"));
      return buildObservation(brandId, proof, "VERIFIED", time);
    }

    @Test
    @DisplayName("Gate Fails: 0 collaborations yields UNAVAILABLE and 0.50 neutral prior")
    void testGate_ZeroCollaborations() {
      M1HistoricalScorer.M1Result result = m1Scorer.evaluate(Collections.emptyList(), "AWARENESS", LocalDateTime.now());
      assertThat(result.isAvailable()).isFalse();
      assertThat(result.m1Availability()).isEqualTo("UNAVAILABLE");
      assertThat(result.m1LearnedScore()).isNull();
      assertThat(result.sm1Norm()).isEqualTo(0.50);
      assertThat(result.eligibleCollaborations()).isEqualTo(0);
      assertThat(result.warnings()).anyMatch(w -> w.contains("Chưa có lịch sử"));
    }

    @Test
    @DisplayName("Gate Fails: 3 collaborations but all from 1 single brand")
    void testGate_SingleBrandFailure() {
      UUID brandA = UUID.randomUUID();
      LocalDateTime now = LocalDateTime.now();
      List<HistoricalCampaign> past = List.of(
          buildCollab(brandA, 5, true, 1.0, "SELF_REPORTED", now.minusMonths(2)),
          buildCollab(brandA, 5, true, 1.0, "SELF_REPORTED", now.minusMonths(4)),
          buildCollab(brandA, 5, true, 1.0, "SELF_REPORTED", now.minusMonths(6)));

      M1HistoricalScorer.M1Result result = m1Scorer.evaluate(past, "AWARENESS", now);
      assertThat(result.isAvailable()).isFalse();
      assertThat(result.distinctBrandCount()).isEqualTo(1);
      assertThat(result.eligibleCollaborations()).isEqualTo(3);
      assertThat(result.sm1Norm()).isEqualTo(0.50);
      assertThat(result.warnings()).anyMatch(w -> w.contains("M1_GATE_FAILED"));
    }

    @Test
    @DisplayName("Gate Passes: >= 3 collaborations from >= 2 brands computes R, T, K scores")
    void testGate_Passes_ComputesRTK() {
      UUID brandA = UUID.randomUUID();
      UUID brandB = UUID.randomUUID();
      LocalDateTime now = LocalDateTime.now();

      List<HistoricalCampaign> past = List.of(
          buildCollab(brandA, 5, true, 1.0, "VERIFIED", now.minusMonths(2)),
          buildCollab(brandA, 4, true, 0.9, "VERIFIED", now.minusMonths(3)),
          buildCollab(brandB, 5, true, 1.0, "VERIFIED", now.minusMonths(4)));

      M1HistoricalScorer.M1Result result = m1Scorer.evaluate(past, "AWARENESS", now);
      assertThat(result.isAvailable()).isTrue();
      assertThat(result.m1Availability()).isEqualTo("AVAILABLE");
      assertThat(result.eligibleCollaborations()).isEqualTo(3);
      assertThat(result.distinctBrandCount()).isEqualTo(2);
      assertThat(result.rScore()).isNotNull().isBetween(0.0, 1.0);
      assertThat(result.tScore()).isNotNull().isEqualTo(1.0); // all on time
      assertThat(result.kScore()).isNotNull().isBetween(0.8, 1.0);
      assertThat(result.m1LearnedScore()).isCloseTo(90.17, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("Timeliness T: Any late deliverable in a collaboration yields 0.0 for that collaboration")
    void testTimeliness_StrictDeliverableCheck() {
      UUID brandA = UUID.randomUUID();
      UUID brandB = UUID.randomUUID();
      LocalDateTime now = LocalDateTime.now();

      List<HistoricalCampaign> past = List.of(
          buildCollab(brandA, 5, true, 1.0, "VERIFIED", now.minusMonths(1)),
          buildCollab(brandA, 5, false, 1.0, "VERIFIED", now.minusMonths(2)), // late!
          buildCollab(brandB, 5, true, 1.0, "VERIFIED", now.minusMonths(3)));

      M1HistoricalScorer.M1Result result = m1Scorer.evaluate(past, "AWARENESS", now);
      assertThat(result.isAvailable()).isTrue();
      // 2 on time out of 3 -> tScore = 2.0 / 3.0 ~ 0.667
      assertThat(result.tScore()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("KPI Attainment K: Raw attainment is preserved without quality shrinkage")
    void testKpiAttainment_SelfReportedDiscount() {
      UUID brandA = UUID.randomUUID();
      UUID brandB = UUID.randomUUID();
      LocalDateTime now = LocalDateTime.now();

      List<HistoricalCampaign> pastSelfReported = List.of(
          buildCollab(brandA, 5, true, 1.0, "SELF_REPORTED", now.minusMonths(1)),
          buildCollab(brandA, 5, true, 1.0, "SELF_REPORTED", now.minusMonths(2)),
          buildCollab(brandB, 5, true, 1.0, "SELF_REPORTED", now.minusMonths(3)));

      M1HistoricalScorer.M1Result resSelf = m1Scorer.evaluate(pastSelfReported, "AWARENESS", now);

      List<HistoricalCampaign> pastVerified = List.of(
          buildCollab(brandA, 5, true, 1.0, "VERIFIED", now.minusMonths(1)),
          buildCollab(brandA, 5, true, 1.0, "VERIFIED", now.minusMonths(2)),
          buildCollab(brandB, 5, true, 1.0, "VERIFIED", now.minusMonths(3)));
      M1HistoricalScorer.M1Result resVer = m1Scorer.evaluate(pastVerified, "AWARENESS", now);

      // Raw attainment is NOT mutated
      assertThat(resSelf.kScore()).isCloseTo(1.00, org.assertj.core.data.Offset.offset(0.01));
      assertThat(resVer.kScore()).isCloseTo(1.00, org.assertj.core.data.Offset.offset(0.01));

      // Evidence quality remains separate metadata and does not shrink raw attainment.
      assertThat(resSelf.sm1Norm()).isEqualTo(resVer.sm1Norm());
      assertThat(resSelf.warnings()).anyMatch(w -> w.contains("K_SELF_REPORTED"));
    }

    @Test
    @DisplayName("High brand concentration warning when maxBrandShare > 0.80")
    void testBrandConcentrationWarning() {
      UUID brandA = UUID.randomUUID();
      UUID brandB = UUID.randomUUID();
      LocalDateTime now = LocalDateTime.now();

      // 5 collabs: 4 from brandA (80%), 1 from brandB (20%) -> border
      // 6 collabs: 5 from brandA (83.3%) -> > 80% triggers warning
      List<HistoricalCampaign> past = List.of(
          buildCollab(brandA, 5, true, 1.0, "VERIFIED", now.minusMonths(1)),
          buildCollab(brandA, 5, true, 1.0, "VERIFIED", now.minusMonths(2)),
          buildCollab(brandA, 5, true, 1.0, "VERIFIED", now.minusMonths(3)),
          buildCollab(brandA, 5, true, 1.0, "VERIFIED", now.minusMonths(4)),
          buildCollab(brandA, 5, true, 1.0, "VERIFIED", now.minusMonths(5)),
          buildCollab(brandB, 5, true, 1.0, "VERIFIED", now.minusMonths(6)));

      M1HistoricalScorer.M1Result result = m1Scorer.evaluate(past, "AWARENESS", now);
      assertThat(result.isAvailable()).isTrue();
      assertThat(result.maxBrandShare()).isGreaterThan(0.80);
      assertThat(result.warnings()).anyMatch(w -> w.contains("HIGH_BRAND_CONCENTRATION_RISK"));
    }

    @Test
    @DisplayName("Gate Fails: 3 collaborations but missing brandProfileId (does not invent distinct brands)")
    void testGate_MissingBrandProfileId_FailsGate() {
      LocalDateTime now = LocalDateTime.now();
      // 3 collaborations with null brand profile
      List<HistoricalCampaign> past = List.of(
          buildCollab(null, 5, true, 1.0, "VERIFIED", now.minusMonths(1)),
          buildCollab(null, 5, true, 1.0, "VERIFIED", now.minusMonths(2)),
          buildCollab(null, 5, true, 1.0, "VERIFIED", now.minusMonths(3)));

      M1HistoricalScorer.M1Result result = m1Scorer.evaluate(past, "AWARENESS", now);
      assertThat(result.isAvailable()).isFalse();
      assertThat(result.distinctBrandCount()).isEqualTo(0);
      assertThat(result.warnings()).anyMatch(w -> w.contains("M1_GATE_FAILED"));
    }

    @Test
    @DisplayName("Timeliness T: Missing deliverable timestamps marks T as UNAVAILABLE (null) without fabricating failure")
    void testTimeliness_MissingTimestamps_MakesTUnavailable() {
      UUID brandA = UUID.randomUUID();
      UUID brandB = UUID.randomUUID();
      LocalDateTime now = LocalDateTime.now();

      // Collab 1: on time
      HistoricalCampaign c1 = buildCollab(brandA, 5, true, 1.0, "VERIFIED", now.minusMonths(1));

      // Collab 2: on time
      HistoricalCampaign c2 = buildCollab(brandB, 5, true, 1.0, "VERIFIED", now.minusMonths(2));

      // Collab 3: Missing timeliness fields entirely!
      ObjectNode noTimeProof = objectMapper.createObjectNode()
          .put("evidenceSchemaVersion", "collaboration-completion-evidence-v1")
          .put("brandAccepted", true)
          .put("completionMethod", "WITHOUT_REVIEW")
          .put("managementMode", "COLLABORATIVE")
          .put("outcomeRevision", 1)
          .put("overallRating", 5)
          .put("kpiStatus", "AVAILABLE")
          .put("kpiTarget", 1000)
          .put("kpiActual", 1000);
      HistoricalCampaign c3 = buildObservation(brandA, noTimeProof, "VERIFIED", now.minusMonths(3));

      M1HistoricalScorer.M1Result result = m1Scorer.evaluate(List.of(c1, c2, c3), "AWARENESS", now);
      assertThat(result.isAvailable()).isFalse();
      // Timeliness has missing reliable timestamps -> tScore MUST BE NULL
      // (UNAVAILABLE)!
      assertThat(result.tScore()).isNull();
      // But R and K are valid
      assertThat(result.rScore()).isNotNull();
      assertThat(result.kScore()).isNotNull();
      // Missing T leaves the fixed-weight composite unavailable; ranking uses the neutral prior.
      assertThat(result.sm1Norm()).isEqualTo(0.50);
    }

    @Test
    @DisplayName("Final Review is optional and never synthesized into R")
    void testMissingReviewKeepsRUnavailable() {
      UUID brandA = UUID.randomUUID();
      UUID brandB = UUID.randomUUID();
      LocalDateTime now = LocalDateTime.now();
      var noReview = withoutReview(brandA, now.minusMonths(1));
      var second = withoutReview(brandA, now.minusMonths(2));
      var third = withoutReview(brandB, now.minusMonths(3));

      M1HistoricalScorer.M1Result result =
          m1Scorer.evaluate(List.of(noReview, second, third), "AWARENESS", now);

      assertThat(result.rScore()).isNull();
      assertThat(result.tScore()).isNotNull();
      assertThat(result.kScore()).isNotNull();
      assertThat(result.m1Availability()).isEqualTo("UNAVAILABLE");
    }

    @Test
    @DisplayName("KPI Attainment K: MEASUREMENT_PENDING is skipped and does NOT fabricate 0/failure")
    void testKpi_MeasurementPending_ExcludedFromSample() {
      UUID brandA = UUID.randomUUID();
      UUID brandB = UUID.randomUUID();
      LocalDateTime now = LocalDateTime.now();

      // Collab 1: raw attainment 1.0
      HistoricalCampaign c1 = buildCollab(brandA, 5, true, 1.0, "VERIFIED", now.minusMonths(1));

      // Collab 2: raw attainment 0.8
      HistoricalCampaign c2 = buildCollab(brandB, 5, true, 0.8, "VERIFIED", now.minusMonths(2));

      // Collab 3: MEASUREMENT_PENDING with kpiActual = 0 (post-campaign metric
      // collection in progress)
      ObjectNode pendingProof = objectMapper.createObjectNode()
          .put("evidenceSchemaVersion", "collaboration-completion-evidence-v1")
          .put("brandAccepted", true)
          .put("completionMethod", "WITH_REVIEW")
          .put("managementMode", "COLLABORATIVE")
          .put("outcomeRevision", 1)
          .put("overallRating", 5)
          .put("kpiStatus", "MEASUREMENT_PENDING")
          .put("kpiActual", 0)
          .put("kpiTarget", 1000);
      pendingProof.putArray("deliverables").addObject()
          .put("submissionSource", "CREATOR_PORTAL")
          .put("submittedAt", now.minusMonths(3).minusMinutes(1).toString())
          .put("dueAt", now.minusMonths(3).toString());
      HistoricalCampaign c3 = buildObservation(brandA, pendingProof, "VERIFIED", now.minusMonths(3));

      M1HistoricalScorer.M1Result result = m1Scorer.evaluate(List.of(c1, c2, c3), "AWARENESS", now);
      assertThat(result.isAvailable()).isFalse();
      assertThat(result.kScore()).isNull();
      assertThat(result.kAvailability()).isEqualTo("UNAVAILABLE");
    }
  }

  @Nested
  @DisplayName("Phase 3: Learned M4 Evaluator Hierarchy Tests")
  class LearnedM4EvaluatorTests {

    private UUID brandId = UUID.randomUUID();
    private UUID creatorId = UUID.randomUUID();
    private UUID masterId = UUID.randomUUID();

    @Test
    @DisplayName("Tier 1: DIRECT_BRAND_CREATOR selected when >= 3 direct reviews exist")
    void testTier1_DirectBrandCreator() {
      List<LearnedM4Evaluator.BrandReviewRecord> reviews = List.of(
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, creatorId, masterId, "TIKTOK", "Beauty",
              5),
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, creatorId, masterId, "TIKTOK", "Beauty",
              4),
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, creatorId, masterId, "TIKTOK", "Beauty",
              5));

      LearnedM4Evaluator.M4EvaluationResult result = m4Evaluator.evaluate(
          creatorId, masterId, "TIKTOK", "Beauty", 0.60, reviews);

      assertThat(result.getM4Source()).isEqualTo("DIRECT_BRAND_CREATOR");
      assertThat(result.getDirectReviewCount()).isEqualTo(3);
      assertThat(result.getM4LearnedScore()).isNotNull();
      // Mean rating = (5 + 4 + 5) / 3 = 4.667 -> norm = (4.667 - 1) / 4 = 0.9167
      assertThat(result.getM4LearnedScore()).isCloseTo(0.9167, org.assertj.core.data.Offset.offset(0.01));
      assertThat(result.getM4EffectiveScore()).isEqualTo(result.getM4LearnedScore());
    }

    @Test
    @DisplayName("Tier 2: COHORT_PLATFORM_NICHE selected when < 3 direct but >= 5 reviews from >= 3 distinct masters on (platform, niche)")
    void testTier2_CohortPlatformNiche() {
      UUID m1 = UUID.randomUUID();
      UUID m2 = UUID.randomUUID();
      UUID m3 = UUID.randomUUID();

      List<LearnedM4Evaluator.BrandReviewRecord> reviews = List.of(
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, UUID.randomUUID(), m1, "TIKTOK",
              "Beauty", 4),
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, UUID.randomUUID(), m1, "TIKTOK",
              "Beauty", 4),
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, UUID.randomUUID(), m2, "TIKTOK",
              "Beauty", 5),
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, UUID.randomUUID(), m3, "TIKTOK",
              "Beauty", 4),
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, UUID.randomUUID(), m3, "TIKTOK",
              "Beauty", 5));

      LearnedM4Evaluator.M4EvaluationResult result = m4Evaluator.evaluate(
          creatorId, masterId, "TIKTOK", "Beauty", 0.50, reviews);

      assertThat(result.getM4Source()).isEqualTo("COHORT_PLATFORM_NICHE");
      assertThat(result.getCohortReviewCount()).isEqualTo(5);
      assertThat(result.getCohortMasterCount()).isEqualTo(3);
      assertThat(result.getM4LearnedScore()).isNotNull();
    }

    @Test
    @DisplayName("Tier 3: COHORT_PLATFORM_ONLY selected when niche cohort lacks distinct masters but platform has >= 5 from >= 3 masters")
    void testTier3_CohortPlatformOnly() {
      UUID m1 = UUID.randomUUID();
      UUID m2 = UUID.randomUUID();
      UUID m3 = UUID.randomUUID();

      List<LearnedM4Evaluator.BrandReviewRecord> reviews = List.of(
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, UUID.randomUUID(), m1, "TIKTOK", "Tech",
              4),
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, UUID.randomUUID(), m1, "TIKTOK",
              "Gaming", 4),
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, UUID.randomUUID(), m2, "TIKTOK",
              "Fashion", 5),
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, UUID.randomUUID(), m3, "TIKTOK",
              "Lifestyle", 4),
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, UUID.randomUUID(), m3, "TIKTOK", "Food",
              5));

      // Candidate is on TIKTOK, but niche is "Beauty" (which has 0 reviews)
      LearnedM4Evaluator.M4EvaluationResult result = m4Evaluator.evaluate(
          creatorId, masterId, "TIKTOK", "Beauty", 0.50, reviews);

      assertThat(result.getM4Source()).isEqualTo("COHORT_PLATFORM_ONLY");
      assertThat(result.getCohortReviewCount()).isEqualTo(5);
      assertThat(result.getCohortMasterCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Tier 4: EXPLICIT_FALLBACK when evidence is insufficient across all tiers")
    void testTier4_ExplicitFallback() {
      // Only 2 reviews total
      List<LearnedM4Evaluator.BrandReviewRecord> reviews = List.of(
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, UUID.randomUUID(), UUID.randomUUID(),
              "TIKTOK", "Beauty", 4),
          new LearnedM4Evaluator.BrandReviewRecord(UUID.randomUUID(), brandId, UUID.randomUUID(), UUID.randomUUID(),
              "TIKTOK", "Beauty", 4));

      LearnedM4Evaluator.M4EvaluationResult result = m4Evaluator.evaluate(
          creatorId, masterId, "TIKTOK", "Beauty", 0.65, reviews);

      assertThat(result.getM4Source()).isEqualTo("EXPLICIT_FALLBACK");
      assertThat(result.getM4LearnedScore()).isNull();
      assertThat(result.getM4ExplicitScore()).isEqualTo(0.65);
      assertThat(result.getM4EffectiveScore()).isEqualTo(0.65);
    }
  }

  @Nested
  @DisplayName("Phase 4 & 5: Recommendation Pool Policy & Immutable Snapshot Audit Integration")
  class PoolPolicyAndSnapshotIntegrationTests {

    private AiRecommendationService recommendationService;
    private SubscriptionService subscriptionService;
    private CampaignOwnershipService ownership;
    private CampaignContextRepository campaignContextRepository;
    private PublicCreatorMetricRepository publicCreatorMetricRepository;
    private HistoricalCampaignRepository historicalCampaignRepository;
    private BrandPatternRepository brandPatternRepository;
    private ShortlistItemRepository shortlistItemRepository;
    private RecommendationRunRepository recommendationRunRepository;
    private AiServiceClient aiServiceClient;
    private PostRepository postRepository;

    private UUID campaignId;
    private UUID brandProfileId;
    private Campaign campaign;

    @BeforeEach
    void setUp() {
      TestActors.authenticate(TestActors.user(Role.BRAND));
      subscriptionService = mock(SubscriptionService.class);
      ownership = mock(CampaignOwnershipService.class);
      campaignContextRepository = mock(CampaignContextRepository.class);
      publicCreatorMetricRepository = mock(PublicCreatorMetricRepository.class);
      historicalCampaignRepository = mock(HistoricalCampaignRepository.class);
      brandPatternRepository = mock(BrandPatternRepository.class);
      shortlistItemRepository = mock(ShortlistItemRepository.class);
      recommendationRunRepository = mock(RecommendationRunRepository.class);
      aiServiceClient = mock(AiServiceClient.class);
      postRepository = mock(PostRepository.class);

      recommendationService = new AiRecommendationService(
          subscriptionService,
          ownership,
          campaignContextRepository,
          publicCreatorMetricRepository,
          historicalCampaignRepository,
          brandPatternRepository,
          shortlistItemRepository,
          recommendationRunRepository,
          aiServiceClient,
          objectMapper);

      recommendationService.setPostRepository(postRepository);
      TestM4Fixtures.wire(recommendationService);

      campaignId = UUID.randomUUID();
      brandProfileId = UUID.randomUUID();
      BrandProfile brandProfile = BrandProfile.builder().id(brandProfileId).build();

      campaign = Campaign.builder()
          .id(campaignId)
          .brandProfile(brandProfile)
          .name("Winter Fashion Launch")
          .niches(List.of("Fashion", "Lifestyle"))
          .platforms(List.of("TIKTOK"))
          .objective(CampaignObjective.AWARENESS)
          .followerMin(10_000L)
          .followerMax(500_000L)
          .build();

      when(ownership.requireOwned(campaignId)).thenReturn(campaign);
      when(shortlistItemRepository.countByCampaignIdAndStatusNot(any(), any())).thenReturn(5L);
      when(subscriptionService.reserveRecommendationQuota(brandProfileId)).thenReturn(UUID.randomUUID());
      when(aiServiceClient.getEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));
      when(recommendationRunRepository.save(any(RecommendationRun.class)))
          .thenAnswer(inv -> {
            RecommendationRun r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
          });
    }

    @AfterEach
    void tearDown() {
      SecurityContextHolder.clearContext();
    }

    private RecommendationCandidateProjection mockCandidate(UUID id, UUID masterId, String platform, String niche) {
      RecommendationCandidateProjection c = mock(RecommendationCandidateProjection.class);
      when(c.getCreatorId()).thenReturn(id);
      when(c.getMasterId()).thenReturn(masterId);
      when(c.getPlatform()).thenReturn(platform);
      when(c.getAccountStatus()).thenReturn("ACTIVE");
      when(c.getCreatorType()).thenReturn("KOC");
      when(c.getUserName()).thenReturn("koc_" + id.toString().substring(0, 6));
      when(c.getDisplayName()).thenReturn("KOC " + id.toString().substring(0, 6));
      when(c.getFollowers()).thenReturn(50_000L);
      when(c.getAvgViews()).thenReturn(BigDecimal.valueOf(20_000));
      when(c.getAvgLikes()).thenReturn(BigDecimal.valueOf(1_000));
      when(c.getEngagementRate()).thenReturn(BigDecimal.valueOf(0.05));
      when(c.getNiche()).thenReturn(niche);
      when(c.getCategory()).thenReturn(niche);
      when(c.getSemanticScore()).thenReturn(0.85);
      return c;
    }

    private List<Post> mockPosts(UUID creatorId) {
      Creator c = Creator.builder().id(creatorId).build();
      return List.of(
          Post.builder().id(UUID.randomUUID()).creator(c).views(18_000L).likes(900L).build(),
          Post.builder().id(UUID.randomUUID()).creator(c).views(20_000L).likes(1_000L).build(),
          Post.builder().id(UUID.randomUUID()).creator(c).views(22_000L).likes(1_100L).build());
    }

    @Test
    @DisplayName("Candidate Partitioning with M1 Gate Enforced: SCORE_READY with >= 3 collabs from >= 2 brands -> Main Pool; lacking M1 gate -> Provisional Pool")
    void testCandidateRouting_WithM1GateEnforced() {
      recommendationService.setM1GateEnforced(true);

      UUID kocMainId = UUID.randomUUID();
      UUID kocProvId = UUID.randomUUID();

      RecommendationCandidateProjection candMain = mockCandidate(kocMainId, null, "TIKTOK", "Fashion");
      RecommendationCandidateProjection candProv = mockCandidate(kocProvId, null, "TIKTOK", "Fashion");

      List<Post> posts = new ArrayList<>();
      posts.addAll(mockPosts(kocMainId));
      posts.addAll(mockPosts(kocProvId));
      when(postRepository.findByCreatorIdIn(any())).thenReturn(posts);

      when(publicCreatorMetricRepository.findCandidatesByShortlist(any(), anyString(), anyBoolean(), any()))
          .thenReturn(List.of(candMain, candProv));

      // kocMain has 3 collabs from 2 brands in past 6 months -> passes M1 Gate
      UUID b1 = UUID.randomUUID(), b2 = UUID.randomUUID();
      LocalDateTime now = LocalDateTime.now();
      ObjectNode proof = objectMapper.createObjectNode()
          .put("evidenceSchemaVersion", "collaboration-completion-evidence-v1")
          .put("brandAccepted", true)
          .put("completionMethod", "WITH_REVIEW")
          .put("managementMode", "COLLABORATIVE")
          .put("outcomeRevision", 1)
          .put("overallRating", 5)
          .put("brandFitRating", 5)
          .put("wouldCollaborateAgain", true)
          .put("kpiStatus", "AVAILABLE")
          .put("kpiType", "VIEWS")
          .put("kpiTarget", 1000)
          .put("kpiActual", 1000)
          .put("kpiEvidenceQuality", "VERIFIED");
      proof.putArray("deliverables").addObject()
          .put("submissionSource", "CREATOR_PORTAL")
          .put("submittedAt", now.minusDays(2).toString())
          .put("dueAt", now.minusDays(1).toString());

      Collaboration col1 = Collaboration.builder().id(UUID.randomUUID())
          .campaign(
              Campaign.builder().id(UUID.randomUUID()).brandProfile(BrandProfile.builder().id(b1).build()).build())
          .build();
      Collaboration col2 = Collaboration.builder().id(UUID.randomUUID())
          .campaign(
              Campaign.builder().id(UUID.randomUUID()).brandProfile(BrandProfile.builder().id(b1).build()).build())
          .build();
      Collaboration col3 = Collaboration.builder().id(UUID.randomUUID())
          .campaign(
              Campaign.builder().id(UUID.randomUUID()).brandProfile(BrandProfile.builder().id(b2).build()).build())
          .build();

      List<HistoricalCampaign> mainHistory = List.of(
          HistoricalCampaign.builder().id(UUID.randomUUID()).evidenceRootId(UUID.randomUUID()).revisionNumber(1L)
              .status("ACTIVE").evidenceType("COLLABORATION_COMPLETION").collaboration(col1).evidenceValue(proof).recordedAt(now.minusMonths(2)).businessOccurredAt(now.minusMonths(2)).build(),
          HistoricalCampaign.builder().id(UUID.randomUUID()).evidenceRootId(UUID.randomUUID()).revisionNumber(1L)
              .status("ACTIVE").evidenceType("COLLABORATION_COMPLETION").collaboration(col2).evidenceValue(proof).recordedAt(now.minusMonths(3)).businessOccurredAt(now.minusMonths(3)).build(),
          HistoricalCampaign.builder().id(UUID.randomUUID()).evidenceRootId(UUID.randomUUID()).revisionNumber(1L)
              .status("ACTIVE").evidenceType("COLLABORATION_COMPLETION").collaboration(col3).evidenceValue(proof).recordedAt(now.minusMonths(4)).businessOccurredAt(now.minusMonths(4)).build());
      when(historicalCampaignRepository.findByCreatorId(kocMainId)).thenReturn(mainHistory);

      // kocProv has 0 historical campaigns -> fails M1 Gate
      when(historicalCampaignRepository.findByCreatorId(kocProvId)).thenReturn(Collections.emptyList());

      RecommendationResponseDto response = recommendationService.recommendKocs(
          campaignId, 10, CandidateSource.SHORTLIST, DisplayMode.BEST_MATCH);

      // Assertions
      assertThat(response.getRecommendations()).hasSize(1);
      CreatorRecommendationItemDto mainItem = response.getRecommendations().get(0);
      assertThat(mainItem.getCreatorId()).isEqualTo(kocMainId);
      assertThat(mainItem.getRecommendationTier()).isEqualTo("HISTORY_SUPPORTED");
      assertThat(mainItem.getIsScoreReady()).isTrue();
      assertThat(mainItem.getIsProvisional()).isFalse();
      assertThat(mainItem.getScoreBreakdown().getM1Availability()).isEqualTo("AVAILABLE");

      assertThat(response.getProvisionalRecommendations()).hasSize(1);
      CreatorRecommendationItemDto provItem = response.getProvisionalRecommendations().get(0);
      assertThat(provItem.getCreatorId()).isEqualTo(kocProvId);
      assertThat(provItem.getRecommendationTier()).isEqualTo("LIMITED_HISTORY");
      assertThat(provItem.getIsScoreReady()).isFalse();
      assertThat(provItem.getIsProvisional()).isTrue();
      assertThat(provItem.getScoreBreakdown().getM1Availability()).isEqualTo("UNAVAILABLE");
      assertThat(provItem.getScoreBreakdown().getM1Score()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("Immutable Scoring Snapshot stores complete runMetadata, candidates array, and both pools")
    void testImmutableScoringSnapshot_SavedCorrectly() {
      UUID kocId = UUID.randomUUID();
      RecommendationCandidateProjection cand = mockCandidate(kocId, null, "TIKTOK", "Fashion");

      when(postRepository.findByCreatorIdIn(any())).thenReturn(mockPosts(kocId));
      when(publicCreatorMetricRepository.findCandidatesByShortlist(any(), anyString(), anyBoolean(), any()))
          .thenReturn(List.of(cand));

      ArgumentCaptor<RecommendationRun> runCaptor = ArgumentCaptor.forClass(RecommendationRun.class);

      RecommendationResponseDto response = recommendationService.recommendKocs(
          campaignId, 10, CandidateSource.SHORTLIST, DisplayMode.BEST_MATCH);

      verify(recommendationRunRepository).save(runCaptor.capture());
      RecommendationRun savedRun = runCaptor.getValue();

      assertThat(savedRun.getScoringPolicyVersion()).isEqualTo("sp01-evidence-v2");
      assertThat(savedRun.getStatus()).isEqualTo("COMPLETED");

      JsonNode snapshot = savedRun.getScoringSnapshot();
      assertThat(snapshot).isNotNull();
      assertThat(snapshot.get("schemaVersion").asText()).isEqualTo("2.0");

      // Verify runMetadata
      JsonNode meta = snapshot.get("runMetadata");
      assertThat(meta).isNotNull();
      assertThat(meta.has("scoringAsOf")).isTrue();
      assertThat(meta.has("historyCutoff")).isTrue();
      assertThat(meta.get("effectiveWeights").isArray()).isTrue();
      assertThat(meta.get("effectiveWeights")).hasSize(4);
      assertThat(meta.get("rankingMode").asText()).isEqualTo("BEST_MATCH");
      assertThat(meta.has("mainPoolSize")).isTrue();
      assertThat(meta.has("provisionalPoolSize")).isTrue();

      // Verify candidates snapshot contains items
      assertThat(snapshot.get("candidates").isArray()).isTrue();
      assertThat(snapshot.get("candidates")).hasSize(savedRun.getCandidatesCount());

      // Verify response has runMetadata
      assertThat(response.getRunMetadata()).isNotNull();
      assertThat(response.getRunMetadata().get("rankingMode").asText()).isEqualTo("BEST_MATCH");
    }
  }
}
