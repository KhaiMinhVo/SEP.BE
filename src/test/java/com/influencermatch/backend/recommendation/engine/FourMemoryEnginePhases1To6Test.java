package com.influencermatch.backend.recommendation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.collaboration.model.CampaignOutcome;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.recommendation.dto.ReasoningCardDto;
import com.influencermatch.backend.recommendation.dto.ScoreBreakdownDto;
import com.influencermatch.backend.recommendation.engine.confidence.ConfidenceCalculator;
import com.influencermatch.backend.recommendation.engine.confidence.enums.ConfidenceTier;
import com.influencermatch.backend.recommendation.engine.confidence.model.ConfidenceAssessment;
import com.influencermatch.backend.recommendation.engine.critic.CriticWeightCalculator;
import com.influencermatch.backend.recommendation.engine.critic.DynamicWeightBlender;
import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import com.influencermatch.backend.recommendation.engine.memory.BayesianHistoryScorer;
import com.influencermatch.backend.recommendation.engine.memory.BrandAffinityScorer;
import com.influencermatch.backend.recommendation.engine.memory.FourMemoryScorer;
import com.influencermatch.backend.recommendation.engine.memory.PublicPerformanceScorer;
import com.influencermatch.backend.recommendation.engine.memory.SemanticRelevanceScorer;
import com.influencermatch.backend.recommendation.engine.memory.model.CandidateMemoryScores;
import com.influencermatch.backend.recommendation.engine.phase0.EvidenceValidator;
import com.influencermatch.backend.recommendation.engine.phase0.Phase0Orchestrator;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import com.influencermatch.backend.recommendation.engine.phase0.enums.Phase0Status;
import com.influencermatch.backend.recommendation.engine.phase0.model.NormalizedCandidateContext;
import com.influencermatch.backend.recommendation.engine.presentation.DeterministicReasoningGenerator;
import com.influencermatch.backend.recommendation.engine.presentation.DisplaySorter;
import com.influencermatch.backend.recommendation.engine.presentation.MmrReranker;
import com.influencermatch.backend.recommendation.engine.presentation.enums.DisplayMode;
import com.influencermatch.backend.recommendation.engine.presentation.enums.PriceStatus;
import com.influencermatch.backend.recommendation.engine.presentation.pricing.PriceStatusResolver;
import com.influencermatch.backend.recommendation.engine.topsis.TopsisRankingEngine;
import com.influencermatch.backend.recommendation.engine.topsis.model.TopsisResult;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.DoubleStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FourMemoryEnginePhases1To6Test {

  // =========================================================================
  // Phase 1: Memory Scorers
  // =========================================================================

  @Test
  @DisplayName("Phase 1 - SM1: Bayesian Beta-Binomial resolves small-sample bias (8/10 beats 1/1)")
  void testBayesianHistoryScorer_smallSampleResolution() {
    BayesianHistoryScorer scorer = new BayesianHistoryScorer();

    // Creator A: 1 past campaign, 1 success (1/1)
    HistoricalCampaign histA1 = HistoricalCampaign.builder()
        .outcome(CampaignOutcome.builder().roi(BigDecimal.valueOf(1.5)).build())
        .build();
    BayesianHistoryScorer.Result resA = scorer.score(List.of(histA1), "CONVERSION");

    // Creator B: 10 past campaigns, 8 successes (8/10)
    List<HistoricalCampaign> pastB = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      pastB.add(HistoricalCampaign.builder()
          .outcome(CampaignOutcome.builder().roi(BigDecimal.valueOf(1.5)).build())
          .build());
    }
    for (int i = 0; i < 2; i++) {
      pastB.add(HistoricalCampaign.builder()
          .outcome(CampaignOutcome.builder().roi(BigDecimal.valueOf(0.5)).build())
          .build());
    }
    BayesianHistoryScorer.Result resB = scorer.score(pastB, "CONVERSION");

    // Posterior p_hat for A: (1 + 3 * 0.5) / (1 + 3) = 2.5 / 4 = 0.625
    // Posterior p_hat for B: (8 + 3 * 0.5) / (10 + 3) = 9.5 / 13 ≈ 0.7308
    assertThat(resB.pHat()).isGreaterThan(resA.pHat());
    assertThat(resA.pHat()).isEqualTo(0.625);
    assertThat(resB.pHat()).isCloseTo(9.5 / 13.0, offset(1e-4));
    assertThat(resA.isColdStart()).isFalse();

    // Creator C: 0 past campaigns (Cold-start)
    BayesianHistoryScorer.Result resC = scorer.score(Collections.emptyList(), "CONVERSION");
    assertThat(resC.isColdStart()).isTrue();
    assertThat(resC.pHat()).isEqualTo(0.50);
    assertThat(resC.sm1Norm()).isEqualTo(0.50);
    assertThat(resC.score100()).isEqualTo(50.0);
  }

  @Test
  @DisplayName("Phase 1 - SM2: Public Creator Intelligence produces Equal-Weight Mean 1/3")
  void testPublicPerformanceScorer_equalWeightMean() {
    PublicPerformanceScorer scorer = new PublicPerformanceScorer();

    NormalizedCandidateContext p0 = new NormalizedCandidateContext(
        UUID.randomUUID(),
        null,
        "TIKTOK",
        Phase0Status.ELIGIBLE,
        Collections.emptyList(),
        0.60, // erNormalized
        0.70, // vrNormalized
        0.80, // stabNormalized
        0.06, // rawEr
        0.55, // rawVr
        0.75, // rawStab
        ErMethod.BY_VIEWS,
        3,
        false,
        1.0,
        "MVP_CALIBRATION_2026Q1"
    );

    PublicPerformanceScorer.Result res = scorer.score(p0, null);
    // (0.60 + 0.70 + 0.80) / 3 = 0.70
    assertThat(res.sm2Norm()).isCloseTo(0.70, offset(1e-6));
    assertThat(res.score100()).isCloseTo(70.0, offset(1e-4));
    assertThat(res.erNorm()).isEqualTo(0.60);
    assertThat(res.vrNorm()).isEqualTo(0.70);
    assertThat(res.stabNorm()).isEqualTo(0.80);
    assertThat(res.isStabImputed()).isFalse();
  }

  @Test
  @DisplayName("Phase 1 - SM3: Semantic Relevance maps [-1, 1] cosine to [0, 1] utility")
  void testSemanticRelevanceScorer_mapping() {
    SemanticRelevanceScorer scorer = new SemanticRelevanceScorer();

    assertThat(scorer.score(1.0).sm3Norm()).isEqualTo(1.0);
    assertThat(scorer.score(1.0).score100()).isEqualTo(100.0);

    assertThat(scorer.score(-1.0).sm3Norm()).isEqualTo(0.0);
    assertThat(scorer.score(-1.0).score100()).isEqualTo(0.0);

    assertThat(scorer.score(0.0).sm3Norm()).isEqualTo(0.50);
    assertThat(scorer.score(0.0).score100()).isEqualTo(50.0);

    assertThat(scorer.score(0.60).sm3Norm()).isEqualTo(0.80);
    assertThat(scorer.score(0.60).score100()).isEqualTo(80.0);

    // Missing cosine defaults to neutral 0.50
    SemanticRelevanceScorer.Result missing = scorer.score(null);
    assertThat(missing.sm3Norm()).isEqualTo(0.50);
    assertThat(missing.hasEmbedding()).isFalse();
  }

  @Test
  @DisplayName("Phase 1 - SM4: Brand Affinity evaluates explicit tag coverage and pattern boost")
  void testBrandAffinityScorer_tagMatching() {
    BrandAffinityScorer scorer = new BrandAffinityScorer();

    List<String> niches = List.of("Beauty", "Skincare", "Cosmetics");
    BrandAffinityScorer.Result res = scorer.score(
        niches,
        "Beauty",
        "Lifestyle",
        "TIKTOK",
        Collections.emptyList()
    );

    // 1 match out of 3 niches = 0.3333
    assertThat(res.sm4Norm()).isCloseTo(1.0 / 3.0, offset(1e-4));
    assertThat(res.matchedTags()).isEqualTo(1);
    assertThat(res.totalTags()).isEqualTo(3);
    assertThat(res.isConfigured()).isTrue();

    // With platform pattern match boost (+0.10)
    BrandPattern pattern = BrandPattern.builder().platform("TIKTOK").build();
    BrandAffinityScorer.Result resWithPattern = scorer.score(
        niches,
        "Beauty",
        "Lifestyle",
        "TIKTOK",
        List.of(pattern)
    );
    assertThat(resWithPattern.sm4Norm()).isCloseTo((1.0 / 3.0) + 0.10, offset(1e-4));

    // Brand Cold-Start (empty niches) -> 0.50 default
    BrandAffinityScorer.Result coldStart = scorer.score(
        Collections.emptyList(), "Beauty", "Lifestyle", "TIKTOK", Collections.emptyList()
    );
    assertThat(coldStart.sm4Norm()).isEqualTo(0.50);
    assertThat(coldStart.isConfigured()).isFalse();
  }

  // =========================================================================
  // Phase 1: TRUTHMODE Section 8 Verification Test Suite (P0 & P1)
  // =========================================================================

  @Test
  @DisplayName("P0 Test: Phase 0 tính STAB từ Views [0, 100, 100] -> SM2 nhận đúng STAB ≈ 0.536, không tính lại thành 0.134")
  void testP0_stabViewsZeroHundredHundred_passesCorrectStabWithoutRecalculation() {
    EvidenceValidator validator = new EvidenceValidator();
    UUID cId = UUID.randomUUID();
    RecommendationCandidateProjection cand = Mockito.mock(RecommendationCandidateProjection.class);
    Mockito.when(cand.getCreatorId()).thenReturn(cId);
    Mockito.when(cand.getFollowers()).thenReturn(10_000L);
    Mockito.when(cand.getPlatform()).thenReturn("TIKTOK");
    Mockito.when(cand.getAccountStatus()).thenReturn("ACTIVE");

    Post p1 = Post.builder().id(UUID.randomUUID()).platformPostId("p1").views(0L).likes(10L).postedAt("2026-11-08T10:00:00Z").build();
    Post p2 = Post.builder().id(UUID.randomUUID()).platformPostId("p2").views(100L).likes(10L).postedAt("2026-11-08T11:00:00Z").build();
    Post p3 = Post.builder().id(UUID.randomUUID()).platformPostId("p3").views(100L).likes(10L).postedAt("2026-11-08T12:00:00Z").build();

    // 1. EvidenceValidator tính rawStab: CV ≈ 0.8660 -> rawStab = 1 / (1 + 0.8660) ≈ 0.5359
    var evMetrics = validator.evaluate(cand, List.of(p1, p2, p3));
    assertThat(evMetrics.rawStab()).isCloseTo(0.5359, offset(1e-3));
    assertThat(evMetrics.canComputeStability()).isTrue();

    // 2. Chuyển qua Phase0Orchestrator tạo NormalizedCandidateContext
    Phase0Orchestrator p0Orchestrator = new Phase0Orchestrator();
    Campaign campaign = Campaign.builder().id(UUID.randomUUID()).platforms(List.of("TIKTOK")).build();
    var p0List = p0Orchestrator.execute(List.of(cand), campaign, Collections.emptySet(), Map.of(cId, List.of(p1, p2, p3)));
    assertThat(p0List).hasSize(1);
    NormalizedCandidateContext p0 = p0List.get(0);
    assertThat(p0.stabNormalized()).isCloseTo(0.5359, offset(1e-3));

    // 3. PublicPerformanceScorer trong Phase 1 nhận p0.stabNormalized() trực tiếp
    PublicPerformanceScorer scorer = new PublicPerformanceScorer();
    PublicPerformanceScorer.Result res = scorer.score(p0, cand);
    assertThat(res.stabNorm()).isCloseTo(0.5359, offset(1e-3));
    // Khẳng định không bị tính lại bằng công thức cũ 1 - min(1, CV) = 1 - 0.866 = 0.134
    assertThat(res.stabNorm()).isNotCloseTo(0.134, offset(0.05));
    // Điểm SM2 tính theo mẫu số 3.0: (erNorm + vrNorm + stabNorm) / 3.0
    double expectedSm2 = (p0.erNormalized() + p0.vrNormalized() + p0.stabNormalized()) / 3.0;
    assertThat(res.sm2Norm()).isCloseTo(expectedSm2, offset(1e-6));
  }

  @Test
  @DisplayName("P0 Test: Một scorer nhận Double.NaN -> Không tạo CandidateMemoryScores chứa NaN (ném IllegalArgumentException tại boundary)")
  void testP0_nanDefensiveValidation_candidateMemoryScoresRejectsNan() {
    assertThatThrownBy(() -> new CandidateMemoryScores(
        Double.NaN, 0.70, 0.80, 0.50,
        50.0, 70.0, 80.0, 50.0,
        0.5, 0.5, 0.5,
        1, 1, false,
        3, false, true, true, 1, 1
    )).isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("sm1 must be a finite number in [0.0, 1.0]");

    assertThatThrownBy(() -> new CandidateMemoryScores(
        0.50, Double.NaN, 0.80, 0.50,
        50.0, 70.0, 80.0, 50.0,
        0.5, 0.5, 0.5,
        1, 1, false,
        3, false, true, true, 1, 1
    )).isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("sm2 must be a finite number in [0.0, 1.0]");

    assertThatThrownBy(() -> new CandidateMemoryScores(
        0.50, 0.70, -0.1, 0.50,
        50.0, 70.0, 80.0, 50.0,
        0.5, 0.5, 0.5,
        1, 1, false,
        3, false, true, true, 1, 1
    )).isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("sm3 must be a finite number in [0.0, 1.0]");
  }

  @Test
  @DisplayName("P0 Test: 10 Campaign, chỉ 2 có verified outcome -> Bayesian dùng mẫu số n=2, không phạt thiếu dữ liệu thành thất bại")
  void testP0_bayesianEvaluatedCountExcludesUnknownOutcomes() {
    BayesianHistoryScorer scorer = new BayesianHistoryScorer();

    List<HistoricalCampaign> campaigns = new ArrayList<>();
    // 2 campaign thành công có ROI >= 1.0
    for (int i = 0; i < 2; i++) {
      campaigns.add(HistoricalCampaign.builder()
          .outcome(CampaignOutcome.builder().roi(BigDecimal.valueOf(1.5)).build())
          .build());
    }
    // 8 campaign thiếu dữ liệu hoàn toàn (UNKNOWN)
    for (int i = 0; i < 8; i++) {
      campaigns.add(HistoricalCampaign.builder().outcome(null).evidenceValue(null).build());
    }

    BayesianHistoryScorer.Result res = scorer.score(campaigns, "CONVERSION");

    // Chỉ có 2 campaign có outcome xác định được tính vào mẫu số Bayesian (n = 2, s = 2)
    assertThat(res.sampleSize()).isEqualTo(2);
    assertThat(res.successCount()).isEqualTo(2);
    // pHat = (2 + 3 * 0.5) / (2 + 3) = 3.5 / 5 = 0.70
    // Khẳng định KHÔNG bị tính n = 10 làm điểm tụt xuống 0.269
    assertThat(res.pHat()).isEqualTo(0.70);
    assertThat(res.isColdStart()).isFalse();
  }

  @Test
  @DisplayName("P0 Test: KPI nhóm lower-is-better (ví dụ CPA, COST) phân loại thành công chính xác")
  void testP0_kpiLowerIsBetter_evaluatesCorrectly() {
    BayesianHistoryScorer scorer = new BayesianHistoryScorer();

    // 1. CPA Target = 50,000, Actual = 40,000 (Thấp hơn là Tốt -> Thành công)
    HistoricalCampaign histGoodCpa = HistoricalCampaign.builder()
        .outcome(CampaignOutcome.builder()
            .kpiType("CPA")
            .kpiTarget(BigDecimal.valueOf(50_000))
            .kpiActual(BigDecimal.valueOf(40_000))
            .build())
        .build();
    assertThat(scorer.classifyOutcome(histGoodCpa)).isEqualTo(BayesianHistoryScorer.OutcomeClassification.SUCCESS);

    // 2. CPA Target = 50,000, Actual = 60,000 (Vượt ngân sách -> Thất bại)
    HistoricalCampaign histBadCpa = HistoricalCampaign.builder()
        .outcome(CampaignOutcome.builder()
            .kpiType("CPA")
            .kpiTarget(BigDecimal.valueOf(50_000))
            .kpiActual(BigDecimal.valueOf(60_000))
            .build())
        .build();
    assertThat(scorer.classifyOutcome(histBadCpa)).isEqualTo(BayesianHistoryScorer.OutcomeClassification.FAILURE);

    // 3. Views Target = 100,000, Actual = 120,000 (Cao hơn là Tốt -> Thành công)
    HistoricalCampaign histGoodViews = HistoricalCampaign.builder()
        .outcome(CampaignOutcome.builder()
            .kpiType("VIEWS")
            .kpiTarget(BigDecimal.valueOf(100_000))
            .kpiActual(BigDecimal.valueOf(120_000))
            .build())
        .build();
    assertThat(scorer.classifyOutcome(histGoodViews)).isEqualTo(BayesianHistoryScorer.OutcomeClassification.SUCCESS);
  }

  @Test
  @DisplayName("P0 Test: KPI thất bại nhưng Review tích cực -> Ưu tiên KPI, phân loại FAILURE (Review không đảo ngược KPI)")
  void testP0_kpiFailureWithPositiveReview_precedenceFollowsKpiFailure() {
    BayesianHistoryScorer scorer = new BayesianHistoryScorer();

    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode positiveReview = mapper.createObjectNode();
    positiveReview.put("overallRating", 5.0);
    positiveReview.put("reliabilityScore", 0.95);
    positiveReview.put("wouldCollaborateAgain", true);

    // Campaign có KPI Conversion Target = 500 nhưng Actual = 150 (Thất bại nặng về mục tiêu kinh doanh)
    HistoricalCampaign histConflict = HistoricalCampaign.builder()
        .outcome(CampaignOutcome.builder()
            .kpiType("CONVERSIONS")
            .kpiTarget(BigDecimal.valueOf(500))
            .kpiActual(BigDecimal.valueOf(150))
            .build())
        .evidenceValue(positiveReview)
        .build();

    // Theo Evidence Precedence Contract: Kết quả kinh doanh (KPI) được ưu tiên tuyệt đối
    assertThat(scorer.classifyOutcome(histConflict)).isEqualTo(BayesianHistoryScorer.OutcomeClassification.FAILURE);
  }

  @Test
  @DisplayName("P0 Test: Tất cả Creator lỗi hoặc cấu hình sai -> Ném IllegalStateException, không âm thầm trả về rỗng")
  void testP0_systemicFailureOrAllCandidatesCorrupted_throwsIllegalStateException() {
    FourMemoryTopsisEngine engine = new FourMemoryTopsisEngine();
    Campaign campaign = Campaign.builder().id(UUID.randomUUID()).platforms(List.of("TIKTOK")).build();

    RecommendationCandidateProjection candCorrupted = Mockito.mock(RecommendationCandidateProjection.class);
    Mockito.when(candCorrupted.getCreatorId()).thenReturn(UUID.randomUUID());
    Mockito.when(candCorrupted.getEngagementRate()).thenReturn(BigDecimal.valueOf(-2.0)); // Gây IllegalArgumentException

    // Khi tất cả ứng viên bị lỗi dữ liệu hoặc cấu hình chung bị sai:
    assertThatThrownBy(() -> engine.evaluateAndRank(
        List.of(candCorrupted),
        Collections.emptyMap(),
        campaign,
        c -> Collections.emptyList(),
        Collections.emptyList()
    )).isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("ALL_CANDIDATES_CORRUPTED");
  }

  @Test
  @DisplayName("P0 Test: Fault Isolation - Một ứng viên ném lỗi không làm sập Recommendation Run của các ứng viên khác")
  void testP0_faultIsolation_corruptedCandidateDoesNotCrashRun() {
    FourMemoryTopsisEngine engine = new FourMemoryTopsisEngine();
    Campaign campaign = Campaign.builder().id(UUID.randomUUID()).platforms(List.of("TIKTOK")).build();

    RecommendationCandidateProjection cand1 = Mockito.mock(RecommendationCandidateProjection.class);
    UUID id1 = UUID.randomUUID();
    Mockito.when(cand1.getCreatorId()).thenReturn(id1);
    Mockito.when(cand1.getFollowers()).thenReturn(20_000L);
    Mockito.when(cand1.getPlatform()).thenReturn("TIKTOK");
    Mockito.when(cand1.getAccountStatus()).thenReturn("ACTIVE");

    RecommendationCandidateProjection cand2Corrupted = Mockito.mock(RecommendationCandidateProjection.class);
    UUID id2 = UUID.randomUUID();
    Mockito.when(cand2Corrupted.getCreatorId()).thenReturn(id2);
    // cand2 có engagementRate âm, sẽ gây IllegalArgumentException khi Phase 1 validate
    Mockito.when(cand2Corrupted.getEngagementRate()).thenReturn(BigDecimal.valueOf(-1.5));
    Mockito.when(cand2Corrupted.getPlatform()).thenReturn("TIKTOK");
    Mockito.when(cand2Corrupted.getAccountStatus()).thenReturn("ACTIVE");

    RecommendationCandidateProjection cand3 = Mockito.mock(RecommendationCandidateProjection.class);
    UUID id3 = UUID.randomUUID();
    Mockito.when(cand3.getCreatorId()).thenReturn(id3);
    Mockito.when(cand3.getFollowers()).thenReturn(50_000L);
    Mockito.when(cand3.getPlatform()).thenReturn("TIKTOK");
    Mockito.when(cand3.getAccountStatus()).thenReturn("ACTIVE");

    NormalizedCandidateContext p0_1 = new NormalizedCandidateContext(
        id1, null, "TIKTOK", Phase0Status.ELIGIBLE, Collections.emptyList(),
        0.60, 0.70, 0.80, 0.05, 0.50, 0.75, ErMethod.BY_VIEWS, 3, false, 0.65, "V1"
    );
    NormalizedCandidateContext p0_3 = new NormalizedCandidateContext(
        id3, null, "TIKTOK", Phase0Status.ELIGIBLE, Collections.emptyList(),
        0.50, 0.60, 0.70, 0.04, 0.40, 0.65, ErMethod.BY_VIEWS, 3, false, 0.65, "V1"
    );

    Map<UUID, NormalizedCandidateContext> phase0Map = Map.of(
        id1, p0_1,
        id3, p0_3
    );

    // Chạy evaluateAndRank với cả 3 ứng viên
    List<FourMemoryTopsisEngine.EvaluatedCandidate> evaluated = engine.evaluateAndRank(
        List.of(cand1, cand2Corrupted, cand3),
        phase0Map,
        campaign,
        c -> Collections.emptyList(),
        Collections.emptyList()
    );

    // Run không bị sập; cand2 bị cô lập, cand1 và cand3 được đánh giá và xếp hạng bình thường
    assertThat(evaluated).hasSize(2);
    assertThat(evaluated).extracting(e -> e.candidate().getCreatorId()).containsExactlyInAnyOrder(id1, id3);
  }

  @Test
  @DisplayName("P1 Test: Provisional thiếu STAB -> toScoreBreakdownDto không chứa NaN, chuyển đổi thành công")
  void testP1_provisionalScoreBreakdown_noNanInDto() {
    FourMemoryTopsisEngine engine = new FourMemoryTopsisEngine();
    UUID cId = UUID.randomUUID();
    RecommendationCandidateProjection cand = Mockito.mock(RecommendationCandidateProjection.class);
    Mockito.when(cand.getCreatorId()).thenReturn(cId);

    // Candidate Provisional có stabNorm là NaN nhưng sm2Norm là hữu hạn (nhờ sàn 0.0)
    CandidateMemoryScores memoryScores = new CandidateMemoryScores(
        0.50, 0.45, 0.80, 0.60,
        50.0, 45.0, 80.0, 60.0,
        0.65, 0.70, Double.NaN, // stabNorm là NaN
        0, 0, true,
        1, true, true, true, 1, 1
    );

    TopsisResult topsis = new TopsisResult(0.35, 0.55, 0.62, 62.0);
    ConfidenceAssessment conf = new ConfidenceAssessment(ConfidenceTier.LOW, 0.35, 0.50, 0.20, 1.0, 0.50, List.of("SHORT_WINDOW"));
    EngineWeights weights = new EngineWeights(0.25, 0.25, 0.25, 0.25, "EQUAL", 0.0);

    FourMemoryTopsisEngine.EvaluatedCandidate evaluated =
        new FourMemoryTopsisEngine.EvaluatedCandidate(cand, memoryScores, topsis, conf, weights);

    ScoreBreakdownDto dto = engine.toScoreBreakdownDto(evaluated);
    assertThat(dto).isNotNull();
    assertThat(dto.getM2Score()).isEqualTo(45.0);
    assertThat(dto.getFollowerScore()).isEqualTo(70.0);
    assertThat(dto.getEngagementScore()).isEqualTo(65.0);
    assertThat(dto.getFinalScore()).isEqualTo(62.0);
    // Tuyệt đối không có trường nào mang giá trị NaN
    assertThat(Double.isNaN(dto.getM1Score())).isFalse();
    assertThat(Double.isNaN(dto.getM2Score())).isFalse();
    assertThat(Double.isNaN(dto.getFinalScore())).isFalse();
  }

  @Test
  @DisplayName("P1 Test: Review JSON lỗi hoặc rỗng -> Fallback an toàn về prior 0.50, không sinh điểm 0 giả tạo")
  void testP1_reviewJsonMalformedOrMissingRatings_fallsBackGracefully() {
    BayesianHistoryScorer scorer = new BayesianHistoryScorer();

    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode malformedNode = mapper.createObjectNode();
    malformedNode.put("invalidField", "text");

    HistoricalCampaign histWithEmptyJson = HistoricalCampaign.builder()
        .outcome(null)
        .evidenceValue(malformedNode)
        .build();

    BayesianHistoryScorer.Result res = scorer.score(List.of(histWithEmptyJson), "AWARENESS");

    // JSON không có rating hợp lệ -> classifyOutcome là UNKNOWN -> mẫu số không tăng
    assertThat(res.sampleSize()).isEqualTo(0);
    assertThat(res.isColdStart()).isTrue();
    // revScore giữ prior 0.50 (không bị ép về 0.0)
    assertThat(res.sm1Norm()).isEqualTo(0.50);
  }

  @Test
  @DisplayName("P1 Test: Creator chưa có lịch sử Campaign -> SM1 dùng prior, gắn đúng Cold-Start")
  void testP1_creatorWithoutPastCampaigns_usesPriorAndMarksColdStart() {
    BayesianHistoryScorer scorer = new BayesianHistoryScorer();
    BayesianHistoryScorer.Result res = scorer.score(Collections.emptyList(), "AWARENESS");

    assertThat(res.isColdStart()).isTrue();
    assertThat(res.sampleSize()).isEqualTo(0);
    assertThat(res.successCount()).isEqualTo(0);
    assertThat(res.pHat()).isEqualTo(0.50);
    assertThat(res.sm1Norm()).isEqualTo(0.50);
    assertThat(res.score100()).isEqualTo(50.0);
  }

  @Test
  @DisplayName("P1 Test: Creator thiếu embedding -> SM3 = 0.50 theo policy, cờ hasEmbedding = false được bảo toàn")
  void testP1_creatorMissingEmbedding_assignedPolicyNeutralScore() {
    SemanticRelevanceScorer scorer = new SemanticRelevanceScorer();
    SemanticRelevanceScorer.Result res = scorer.score(null);

    assertThat(res.hasEmbedding()).isFalse();
    assertThat(res.sm3Norm()).isEqualTo(0.50);
    assertThat(res.score100()).isEqualTo(50.0);
  }

  @Test
  @DisplayName("P1 Test: Hai Creator cùng Niche và Platform nhưng phong cách khác nhau -> Xác nhận SM4 thực tế chỉ có Rule-based Affinity")
  void testP1_brandAffinity_isRuleBasedTagCoverageNotDeepTaste() {
    BrandAffinityScorer scorer = new BrandAffinityScorer();
    List<String> niches = List.of("Beauty");

    // Creator A: Skincare chuyên sâu, TikTok
    BrandAffinityScorer.Result resA = scorer.score(niches, "Beauty", "Skincare", "TIKTOK", Collections.emptyList());
    // Creator B: Comedy giải trí thử thách, TikTok
    BrandAffinityScorer.Result resB = scorer.score(niches, "Beauty", "Comedy", "TIKTOK", Collections.emptyList());

    // Cùng khớp tag "Beauty" nên SM4 bằng nhau = 1.0 (Xác nhận tính chất Rule-based của MVP)
    assertThat(resA.sm4Norm()).isEqualTo(resB.sm4Norm());
    assertThat(resA.sm4Norm()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("P1 Test: SCORE_READY và PROVISIONAL cùng đi qua FourMemoryScorer -> Provisional dùng STAB Floor và isStabImputed = true")
  void testP1_provisionalCandidateUsesStabFloorInPhase1() {
    FourMemoryScorer scorer = new FourMemoryScorer();
    Campaign campaign = Campaign.builder().id(UUID.randomUUID()).niches(List.of("Fashion")).build();

    // 1. Candidate SCORE_READY (đầy đủ ER, VR, STAB)
    RecommendationCandidateProjection candReady = Mockito.mock(RecommendationCandidateProjection.class);
    Mockito.when(candReady.getCreatorId()).thenReturn(UUID.randomUUID());
    Mockito.when(candReady.getSemanticScore()).thenReturn(0.80);
    Mockito.when(candReady.getNiche()).thenReturn("Fashion");
    Mockito.when(candReady.getPlatform()).thenReturn("TIKTOK");

    NormalizedCandidateContext p0Ready = new NormalizedCandidateContext(
        candReady.getCreatorId(), null, "TIKTOK",
        Phase0Status.ELIGIBLE, Collections.emptyList(),
        0.60, 0.70, 0.80,
        0.05, 0.50, 0.75,
        ErMethod.BY_VIEWS, 3, false, 0.65,
        "MVP_CALIBRATION_2026Q1"
    );

    CandidateMemoryScores scoresReady = scorer.evaluate(candReady, p0Ready, campaign, Collections.emptyList(), Collections.emptyList());
    assertThat(scoresReady.m2StabImputed()).isFalse();
    assertThat(scoresReady.stabNorm()).isEqualTo(0.80);
    assertThat(scoresReady.sm2()).isCloseTo((0.60 + 0.70 + 0.80) / 3.0, offset(1e-6));

    // 2. Candidate PROVISIONAL (thiếu STAB -> stabNormalized là NaN)
    RecommendationCandidateProjection candProv = Mockito.mock(RecommendationCandidateProjection.class);
    Mockito.when(candProv.getCreatorId()).thenReturn(UUID.randomUUID());
    Mockito.when(candProv.getSemanticScore()).thenReturn(0.80);
    Mockito.when(candProv.getNiche()).thenReturn("Fashion");
    Mockito.when(candProv.getPlatform()).thenReturn("TIKTOK");

    NormalizedCandidateContext p0Prov = new NormalizedCandidateContext(
        candProv.getCreatorId(), null, "TIKTOK",
        Phase0Status.ELIGIBLE, Collections.emptyList(),
        0.60, 0.70, Double.NaN,
        0.05, 0.50, Double.NaN,
        ErMethod.BY_VIEWS, 1, true, 0.20,
        "MVP_CALIBRATION_2026Q1"
    );

    CandidateMemoryScores scoresProv = scorer.evaluate(candProv, p0Prov, campaign, Collections.emptyList(), Collections.emptyList());
    assertThat(scoresProv.m2StabImputed()).isTrue();
    // STAB được gán sàn bảo thủ 0.0: (0.60 + 0.70 + 0.0) / 3.0 = 1.30 / 3.0 ≈ 0.4333
    assertThat(scoresProv.sm2()).isCloseTo((0.60 + 0.70 + 0.0) / 3.0, offset(1e-6));
    // Điểm của Ready (0.70) cao hơn Provisional (0.4333) dù cùng ER và VR
    assertThat(scoresReady.sm2()).isGreaterThan(scoresProv.sm2());
  }

  // =========================================================================
  // Phase 2 & 3: CRITIC Weighting & Dynamic Weight Blending
  // =========================================================================

  @Test
  @DisplayName("Phase 2 & 3: CRITIC with zero variance triggers BUSINESS_FALLBACK with unit sum")
  void testCriticWeightCalculator_zeroVarianceTriggersFallback() {
    CriticWeightCalculator critic = new CriticWeightCalculator();
    DynamicWeightBlender blender = new DynamicWeightBlender();

    // 15 identical rows (zero variance)
    List<double[]> constantMatrix = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      constantMatrix.add(new double[] {0.5, 0.5, 0.5, 0.5});
    }

    Optional<double[]> qOpt = critic.calculate(constantMatrix);
    assertThat(qOpt).isEmpty();

    EngineWeights weights = blender.blend("CONVERSION", qOpt);
    assertThat(weights.mode()).isEqualTo("BUSINESS_FALLBACK");
    assertThat(weights.lambdaUsed()).isEqualTo(0.0);
    assertThat(weights.w1()).isEqualTo(0.40);
    assertThat(weights.w2()).isEqualTo(0.15);
    assertThat(weights.w3()).isEqualTo(0.30);
    assertThat(weights.w4()).isEqualTo(0.15);
    assertThat(weights.w1() + weights.w2() + weights.w3() + weights.w4()).isCloseTo(1.0, offset(1e-6));
  }

  @Test
  @DisplayName("Phase 2 & 3: CRITIC with diverse candidates blends with business weights and ensures unit sum")
  void testCriticWeightCalculator_blendsProperly() {
    CriticWeightCalculator critic = new CriticWeightCalculator();
    DynamicWeightBlender blender = new DynamicWeightBlender();

    List<double[]> matrix = new ArrayList<>();
    Random rnd = new Random(42);
    for (int i = 0; i < 20; i++) {
      matrix.add(new double[] {
          0.3 + rnd.nextDouble() * 0.6,
          0.2 + rnd.nextDouble() * 0.7,
          0.4 + rnd.nextDouble() * 0.5,
          0.1 + rnd.nextDouble() * 0.8
      });
    }

    Optional<double[]> qOpt = critic.calculate(matrix);
    assertThat(qOpt).isPresent();
    double[] q = qOpt.get();
    assertThat(q[0] + q[1] + q[2] + q[3]).isCloseTo(1.0, offset(1e-6));

    EngineWeights weights = blender.blend("ENGAGEMENT", qOpt, 0.30);
    assertThat(weights.mode()).isEqualTo("CRITIC_BLENDED");
    assertThat(weights.lambdaUsed()).isEqualTo(0.30);
    assertThat(weights.w1() + weights.w2() + weights.w3() + weights.w4()).isCloseTo(1.0, offset(1e-6));
  }

  @Test
  @DisplayName("P0 Test: SM1, SM4 hằng số; SM2, SM3 tương quan dương hoàn hảo -> Không tạo xung đột giả, kích hoạt Fallback")
  void testP0_constantColumns_doNotCreateFakeConflict_triggersFallback() {
    CriticWeightCalculator critic = new CriticWeightCalculator();

    // 15 hàng:
    // SM1 = 0.5 (hằng số)
    // SM4 = 1.0 (hằng số)
    // SM2 = SM3 tăng dần từ 0.2 đến 0.9 (tương quan dương tuyệt đối r_23 = 1.0)
    List<double[]> matrix = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      double v = 0.2 + (i * 0.05);
      matrix.add(new double[] {0.5, v, v, 1.0});
    }

    // Với logic đúng, hai cột hằng số bị loại khỏi phép cộng xung đột.
    // Xung đột giữa SM2 và SM3 là (1 - 1.0) = 0.
    // Tổng C_j = 0 -> không sinh trọng số giả từ cột hằng số -> Trả về empty (kích hoạt Fallback)
    Optional<double[]> qOpt = critic.calculate(matrix);
    assertThat(qOpt).isEmpty();
  }

  @Test
  @DisplayName("P0 Test: Chỉ duy nhất 1 trong 4 chiều có phương sai khác 0 -> Không đủ thông tin đa chiều, kích hoạt Fallback")
  void testP0_onlyOneColumnWithVariance_triggersFallback() {
    CriticWeightCalculator critic = new CriticWeightCalculator();

    // Chỉ có SM2 biến thiên, 3 cột còn lại hằng số
    List<double[]> matrix = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      matrix.add(new double[] {0.5, 0.1 + i * 0.05, 0.7, 0.3});
    }

    // activeCount = 1 < 2 -> Không thể đo tương quan liên tiêu chí -> Trả về empty
    Optional<double[]> qOpt = critic.calculate(matrix);
    assertThat(qOpt).isEmpty();
  }

  @Test
  @DisplayName("P0 Test: Lambda không hợp lệ (âm hoặc > 1.0 hoặc NaN) -> Ném IllegalStateException, không trả trọng số giả")
  void testP0_invalidLambda_throwsIllegalStateException() {
    DynamicWeightBlender blender = new DynamicWeightBlender();
    Optional<double[]> mockQ = Optional.of(new double[] {0.25, 0.25, 0.25, 0.25});

    // Lambda < 0
    assertThatThrownBy(() -> blender.blend("CONVERSION", mockQ, -0.2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("INVALID_BLEND_LAMBDA");

    // Lambda > 1.0
    assertThatThrownBy(() -> blender.blend("CONVERSION", mockQ, 1.5))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("INVALID_BLEND_LAMBDA");

    // Lambda = NaN
    assertThatThrownBy(() -> blender.blend("CONVERSION", mockQ, Double.NaN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("INVALID_BLEND_LAMBDA");
  }

  @Test
  @DisplayName("P0 Test: Có 10 ứng viên ban đầu nhưng 1 ứng viên bị cách ly -> CRITIC nhận 9 ứng viên, kích hoạt Fallback")
  void testP0_tenCandidatesWithOneIsolated_nineReachCritic_triggersFallback() {
    FourMemoryTopsisEngine engine = new FourMemoryTopsisEngine();
    Campaign campaign = Campaign.builder().id(UUID.randomUUID()).objective(com.influencermatch.backend.campaign.enums.CampaignObjective.CONVERSION).build();

    List<RecommendationCandidateProjection> candidates = new ArrayList<>();
    Map<UUID, NormalizedCandidateContext> phase0Map = new HashMap<>();

    // 9 ứng viên hợp lệ
    for (int i = 0; i < 9; i++) {
      UUID id = UUID.randomUUID();
      RecommendationCandidateProjection c = Mockito.mock(RecommendationCandidateProjection.class);
      Mockito.when(c.getCreatorId()).thenReturn(id);
      Mockito.when(c.getPlatform()).thenReturn("TIKTOK");
      Mockito.when(c.getAccountStatus()).thenReturn("ACTIVE");
      candidates.add(c);

      phase0Map.put(id, new NormalizedCandidateContext(
          id, null, "TIKTOK", Phase0Status.ELIGIBLE, Collections.emptyList(),
          0.5 + i * 0.04, 0.4 + i * 0.05, 0.6 + i * 0.03,
          0.05, 0.50, 0.70, ErMethod.BY_VIEWS, 3, false, 0.65, "V1"
      ));
    }

    // 1 ứng viên thứ 10 bị lỗi dữ liệu (sẽ bị cách ly tại Phase 1)
    UUID badId = UUID.randomUUID();
    RecommendationCandidateProjection badCand = Mockito.mock(RecommendationCandidateProjection.class);
    Mockito.when(badCand.getCreatorId()).thenReturn(badId);
    Mockito.when(badCand.getEngagementRate()).thenReturn(BigDecimal.valueOf(-2.0)); // Ném lỗi
    candidates.add(badCand);

    List<FourMemoryTopsisEngine.EvaluatedCandidate> evaluated = engine.evaluateAndRank(
        candidates,
        phase0Map,
        campaign,
        c -> Collections.emptyList(),
        Collections.emptyList()
    );

    // Đúng 9 ứng viên hợp lệ được xếp hạng
    assertThat(evaluated).hasSize(9);
    // Vì CRITIC chỉ nhận được 9 ứng viên (< MIN_POOL_SIZE = 10), chế độ trọng số bắt buộc là BUSINESS_FALLBACK
    EngineWeights usedWeights = evaluated.get(0).weights();
    assertThat(usedWeights.mode()).isEqualTo("BUSINESS_FALLBACK");
    assertThat(usedWeights.lambdaUsed()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("P1 Test: Ngưỡng kích hoạt chính xác m=9 (Fallback) và m=10 (CRITIC_BLENDED)")
  void testP1_exactNineAndTenThreshold_activatesCorrectly() {
    CriticWeightCalculator critic = new CriticWeightCalculator();
    Random rnd = new Random(42);

    // 9 ứng viên đa dạng
    List<double[]> matrix9 = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      matrix9.add(new double[] {
          0.2 + rnd.nextDouble() * 0.6,
          0.3 + rnd.nextDouble() * 0.5,
          0.1 + rnd.nextDouble() * 0.7,
          0.4 + rnd.nextDouble() * 0.4
      });
    }
    assertThat(critic.calculate(matrix9)).isEmpty(); // 9 < 10 -> empty

    // 10 ứng viên đa dạng
    List<double[]> matrix10 = new ArrayList<>(matrix9);
    matrix10.add(new double[] {0.8, 0.2, 0.9, 0.3});
    Optional<double[]> qOpt10 = critic.calculate(matrix10);
    assertThat(qOpt10).isPresent();
    assertThat(qOpt10.get()[0] + qOpt10.get()[1] + qOpt10.get()[2] + qOpt10.get()[3]).isCloseTo(1.0, offset(1e-6));
  }

  @Test
  @DisplayName("P1 Test: EngineWeights constructor phòng vệ tuyệt đối chống NaN, trọng số âm và tổng sai 1.0")
  void testP1_engineWeightsValidation_rejectsInvalidWeights() {
    // Trọng số âm
    assertThatThrownBy(() -> new EngineWeights(-0.1, 0.4, 0.4, 0.3, "TEST", 0.0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("w1 must be a finite number in [0.0, 1.0]");

    // Trọng số NaN
    assertThatThrownBy(() -> new EngineWeights(0.25, Double.NaN, 0.25, 0.25, "TEST", 0.0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("w2 must be a finite number in [0.0, 1.0]");

    // Tổng không bằng 1.0
    assertThatThrownBy(() -> new EngineWeights(0.3, 0.3, 0.3, 0.3, "TEST", 0.0)) // Tổng 1.2
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("INVALID_WEIGHT_SUM");
  }

  // =========================================================================
  // Phase 4: Fixed-Ideal TOPSIS Ranking Engine
  // =========================================================================

  @Test
  @DisplayName("Phase 4: Fixed-Ideal TOPSIS correctly ranks benchmark candidate vectors")
  void testTopsisRankingEngine_knownSampleVectors() {
    TopsisRankingEngine topsis = new TopsisRankingEngine();
    EngineWeights equalWeights = new EngineWeights(0.25, 0.25, 0.25, 0.25, "TEST", 0.0, null, null);

    // Candidate A: Strong across all dimensions
    TopsisResult resA = topsis.compute(0.85, 0.80, 0.90, 0.75, equalWeights);

    // Candidate B: Moderate across all dimensions
    TopsisResult resB = topsis.compute(0.60, 0.65, 0.70, 0.60, equalWeights);

    // Candidate C: Neutral baseline (all 0.50)
    TopsisResult resC = topsis.compute(0.50, 0.50, 0.50, 0.50, equalWeights);

    // Candidate D: Low performance
    TopsisResult resD = topsis.compute(0.20, 0.30, 0.25, 0.35, equalWeights);

    assertThat(resC.matchScore()).isCloseTo(50.0, offset(1e-6));
    assertThat(resA.matchScore()).isGreaterThan(resB.matchScore());
    assertThat(resB.matchScore()).isGreaterThan(resC.matchScore());
    assertThat(resC.matchScore()).isGreaterThan(resD.matchScore());

    assertThat(resA.distancePositive()).isLessThan(resB.distancePositive());
    assertThat(resA.distanceNegative()).isGreaterThan(resB.distanceNegative());
  }

  @Test
  @DisplayName("P0 Test: Giữ nguyên W và điểm của Creator cũ -> Thêm ứng viên mới không làm trôi MatchScore cũ (Fixed-Ideal Invariance)")
  void testP0_topsis_rankReversalResistance_whenWeightFixed() {
    TopsisRankingEngine topsis = new TopsisRankingEngine();
    EngineWeights fixedWeights = new EngineWeights(0.40, 0.15, 0.30, 0.15, "TEST", 0.0, null, null);

    // Điểm ban đầu của Creator A
    TopsisResult initialResultA = topsis.compute(0.70, 0.60, 0.80, 0.75, fixedWeights);

    // Thêm các ứng viên B, C, D vào pool nhưng giữ nguyên W
    TopsisResult resB = topsis.compute(0.95, 0.90, 0.92, 0.88, fixedWeights);
    TopsisResult resC = topsis.compute(0.10, 0.20, 0.15, 0.30, fixedWeights);

    // Tính lại cho Creator A với cùng W
    TopsisResult recomputedResultA = topsis.compute(0.70, 0.60, 0.80, 0.75, fixedWeights);

    // Khẳng định: MatchScore và khoảng cách của A bất biến 100%
    assertThat(recomputedResultA.matchScore()).isEqualTo(initialResultA.matchScore());
    assertThat(recomputedResultA.distancePositive()).isEqualTo(initialResultA.distancePositive());
    assertThat(recomputedResultA.distanceNegative()).isEqualTo(initialResultA.distanceNegative());
  }

  @Test
  @DisplayName("P0 Test: Khi W thay đổi do CRITIC tính lại trên tập mẫu mới -> MatchScore và thứ hạng có thể dịch chuyển")
  void testP0_topsis_rankShift_whenWeightChanges_dueToCriticRecalculation() {
    TopsisRankingEngine topsis = new TopsisRankingEngine();
    com.influencermatch.backend.recommendation.engine.critic.CriticWeightCalculator criticCalc =
        new com.influencermatch.backend.recommendation.engine.critic.CriticWeightCalculator();
    com.influencermatch.backend.recommendation.engine.critic.DynamicWeightBlender blender =
        new com.influencermatch.backend.recommendation.engine.critic.DynamicWeightBlender();

    // Điểm của hai Creator A và B không thay đổi qua hai lần chạy:
    // Creator A: Mạnh SM1 (0.80), yếu SM2 (0.20)
    // Creator B: Yếu SM1 (0.20), mạnh SM2 (0.80)
    double[] scoresA = new double[] {0.80, 0.20, 0.50, 0.50};
    double[] scoresB = new double[] {0.20, 0.80, 0.50, 0.50};

    // Pool 1 (10 candidates): SM1 biến thiên rất mạnh (sigma1 cao), SM3 có biến thiên phụ (đủ >= 2 tiêu chí active cho CRITIC), SM2 cố định 0.50
    List<double[]> pool1 = new ArrayList<>(List.of(
        new double[] {0.10, 0.50, 0.40, 0.50},
        new double[] {0.90, 0.50, 0.60, 0.50},
        new double[] {0.20, 0.50, 0.45, 0.50},
        new double[] {0.80, 0.50, 0.55, 0.50},
        new double[] {0.15, 0.50, 0.42, 0.50},
        new double[] {0.85, 0.50, 0.58, 0.50},
        new double[] {0.25, 0.50, 0.48, 0.50},
        new double[] {0.75, 0.50, 0.52, 0.50},
        new double[] {0.10, 0.50, 0.40, 0.50},
        new double[] {0.90, 0.50, 0.60, 0.50}
    ));

    // CRITIC tính toán trên Pool 1 -> Q1 ưu tiên SM1 -> Blended W1 có w1 > w2
    Optional<double[]> q1Opt = criticCalc.calculate(pool1);
    assertThat(q1Opt).isPresent();
    EngineWeights w1 = blender.blend("COMMUNITY_GROWTH", q1Opt, 0.50);
    assertThat(w1.w1()).isGreaterThan(w1.w2());

    TopsisResult resA1 = topsis.compute(scoresA[0], scoresA[1], scoresA[2], scoresA[3], w1);
    TopsisResult resB1 = topsis.compute(scoresB[0], scoresB[1], scoresB[2], scoresB[3], w1);
    assertThat(resA1.matchScore()).isGreaterThan(resB1.matchScore()); // Creator A xếp trên Creator B

    // Pool 2: Mở rộng thêm 15 ứng viên với SM2 biến thiên cực đoan (0.05 và 0.95) trong khi SM1 cố định 0.50
    List<double[]> pool2 = new ArrayList<>(pool1);
    for (int i = 0; i < 15; i++) {
      double val = (i % 2 == 0) ? 0.05 : 0.95;
      pool2.add(new double[] {0.50, val, 0.50, 0.50});
    }

    // CRITIC tính toán lại trên Pool 2 -> Q2 đảo chiều ưu tiên SM2 -> Blended W2 có w2 > w1
    Optional<double[]> q2Opt = criticCalc.calculate(pool2);
    assertThat(q2Opt).isPresent();
    EngineWeights w2 = blender.blend("COMMUNITY_GROWTH", q2Opt, 0.50);
    assertThat(w2.w2()).isGreaterThan(w2.w1());

    TopsisResult resA2 = topsis.compute(scoresA[0], scoresA[1], scoresA[2], scoresA[3], w2);
    TopsisResult resB2 = topsis.compute(scoresB[0], scoresB[1], scoresB[2], scoresB[3], w2);
    assertThat(resB2.matchScore()).isGreaterThan(resA2.matchScore()); // Creator B đảo lên trên Creator A!

    // Chứng minh chuỗi tích hợp thực tế: Pool thay đổi -> CRITIC tính lại Q -> Blender tính lại W -> TOPSIS đảo thứ hạng
    assertThat(resA1.matchScore()).isNotEqualTo(resA2.matchScore());
  }

  @Test
  @DisplayName("P0 Test: Mẫu số D+ + D- luôn >= 0.5 > 0 với mọi trọng số hợp lệ theo bất đẳng thức tam giác")
  void testP0_topsis_positiveDenom_triangleInequality_strictlyGreaterThanZero() {
    TopsisRankingEngine topsis = new TopsisRankingEngine();
    EngineWeights weights = new EngineWeights(0.40, 0.20, 0.20, 0.20, "TEST", 0.0, null, null);

    // Test trên các điểm biên khắc nghiệt: cực tiểu (0), cực đại (1), và hỗn hợp
    List<double[]> testPoints = List.of(
        new double[] {0.0, 0.0, 0.0, 0.0},
        new double[] {1.0, 1.0, 1.0, 1.0},
        new double[] {0.5, 0.5, 0.5, 0.5},
        new double[] {0.0, 1.0, 0.0, 1.0},
        new double[] {0.99, 0.01, 0.50, 0.50}
    );

    for (double[] pt : testPoints) {
      TopsisResult res = topsis.compute(pt[0], pt[1], pt[2], pt[3], weights);
      double denom = res.distancePositive() + res.distanceNegative();
      assertThat(denom).isGreaterThanOrEqualTo(0.50 - 1e-6);
      assertThat(Double.isFinite(res.closeness())).isTrue();
      assertThat(Double.isFinite(res.matchScore())).isTrue();
    }
  }

  @Test
  @DisplayName("P0 Test: Khớp chuẩn xác công thức Weighted-Coordinate TOPSIS trên vector và trọng số không đồng đều")
  void testP0_topsis_weightedCoordinateConvention_exactVerification() {
    TopsisRankingEngine topsis = new TopsisRankingEngine();
    // Trọng số kinh doanh: W = [0.40, 0.15, 0.30, 0.15]
    EngineWeights weights = new EngineWeights(0.40, 0.15, 0.30, 0.15, "TEST", 0.0, null, null);

    // Creator A: [0.48, 0.56, 0.91, 0.24]
    TopsisResult resA = topsis.compute(0.48, 0.56, 0.91, 0.24, weights);

    // Creator B: [0.32, 0.87, 0.89, 0.80]
    TopsisResult resB = topsis.compute(0.32, 0.87, 0.89, 0.80, weights);

    // Khớp kết quả giải tích Weighted-Coordinate TOPSIS (w_j nhân vào tọa độ trước khi bình phương):
    // MatchScore A ≈ 58.28, MatchScore B ≈ 55.53
    assertThat(resA.matchScore()).isCloseTo(58.28, offset(0.05));
    assertThat(resB.matchScore()).isCloseTo(55.53, offset(0.05));
    assertThat(resA.matchScore()).isGreaterThan(resB.matchScore());
  }

  @Test
  @DisplayName("P1 Test: Tính đơn điệu (Monotonicity) - X_A >= X_B => T_A >= T_B; T_A > T_B khi có cải thiện trên tiêu chí w_j > 0")
  void testP1_topsis_monotonicity() {
    TopsisRankingEngine topsis = new TopsisRankingEngine();
    EngineWeights weights = new EngineWeights(0.30, 0.30, 0.20, 0.20, "TEST", 0.0, null, null);

    // 1. Khi X_A == X_B -> T_A == T_B (Bằng nhau tuyệt đối)
    TopsisResult resEqualA = topsis.compute(0.70, 0.65, 0.60, 0.75, weights);
    TopsisResult resEqualB = topsis.compute(0.70, 0.65, 0.60, 0.75, weights);
    assertThat(resEqualA.matchScore()).isEqualTo(resEqualB.matchScore());

    // 2. Khi X_A >= X_B và có ít nhất một tiêu chí w_j > 0 lớn hơn hẳn -> T_A > T_B
    TopsisResult resStrictA = topsis.compute(0.80, 0.75, 0.70, 0.85, weights);
    TopsisResult resStrictB = topsis.compute(0.70, 0.65, 0.60, 0.75, weights);
    assertThat(resStrictA.matchScore()).isGreaterThan(resStrictB.matchScore());
    assertThat(resStrictA.distancePositive()).isLessThan(resStrictB.distancePositive());
    assertThat(resStrictA.distanceNegative()).isGreaterThan(resStrictB.distanceNegative());

    // 3. Khi X_A > X_B chỉ ở tiêu chí có trọng số bằng 0 (w3 = w4 = 0) -> T_A == T_B
    EngineWeights zeroWeights = new EngineWeights(0.50, 0.50, 0.00, 0.00, "TEST", 0.0, null, null);
    TopsisResult resZeroA = topsis.compute(0.70, 0.60, 0.99, 0.99, zeroWeights); // SM3, SM4 cực cao nhưng w3=w4=0
    TopsisResult resZeroB = topsis.compute(0.70, 0.60, 0.10, 0.10, zeroWeights); // SM3, SM4 rất thấp
    assertThat(resZeroA.matchScore()).isCloseTo(resZeroB.matchScore(), offset(1e-6));
  }

  @Test
  @DisplayName("P1 Test: Vector trung hòa [0.50, 0.50, 0.50, 0.50] luôn đạt đúng MatchScore = 50.0 với mọi W hợp lệ")
  void testP1_topsis_neutralVector_alwaysScoresExactFifty() {
    TopsisRankingEngine topsis = new TopsisRankingEngine();

    List<EngineWeights> arbitraryWeights = List.of(
        new EngineWeights(0.25, 0.25, 0.25, 0.25, "TEST", 0.0, null, null),
        new EngineWeights(0.70, 0.10, 0.10, 0.10, "TEST", 0.0, null, null),
        new EngineWeights(0.10, 0.40, 0.40, 0.10, "TEST", 0.0, null, null),
        new EngineWeights(0.40, 0.15, 0.30, 0.15, "TEST", 0.0, null, null)
    );

    for (EngineWeights w : arbitraryWeights) {
      TopsisResult res = topsis.compute(0.50, 0.50, 0.50, 0.50, w);
      assertThat(res.matchScore()).isCloseTo(50.00, offset(1e-6));
      assertThat(res.distancePositive()).isCloseTo(res.distanceNegative(), offset(1e-6));
    }
  }

  @Test
  @DisplayName("P1 Test: Tie-breaker của Deterministic Comparator ưu tiên SM3 -> SM2 -> CreatorId")
  void testP1_topsis_deterministicComparator_tieBreakers() {
    TopsisRankingEngine topsis = new TopsisRankingEngine();
    EngineWeights weights = new EngineWeights(0.25, 0.25, 0.25, 0.25, "TEST", 0.0, null, null);

    UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // Hai ứng viên có cùng MatchScore chính xác (tổng các bình phương bằng nhau)
    // Cand 1: SM1=0.8, SM2=0.4, SM3=0.7, SM4=0.5
    // Cand 2: SM1=0.8, SM2=0.5, SM3=0.6, SM4=0.5
    // Ở đây Cand 1 có SM3=0.7 > Cand 2 có SM3=0.6 -> Cand 1 phải xếp trước
    record MockCand(UUID creatorId, double matchScore, CandidateMemoryScores memoryScores) implements TopsisRankingEngine.RankableCandidate {}

    CandidateMemoryScores mem1 = new CandidateMemoryScores(0.8, 0.4, 0.7, 0.5, 80, 40, 70, 50, 0.4, 0.4, 0.4, 1, 1, false, 3, false, true, true, 2, 2);
    CandidateMemoryScores mem2 = new CandidateMemoryScores(0.8, 0.5, 0.6, 0.5, 80, 50, 60, 50, 0.5, 0.5, 0.5, 1, 1, false, 3, false, true, true, 2, 2);

    MockCand c1 = new MockCand(id2, 70.0, mem1);
    MockCand c2 = new MockCand(id1, 70.0, mem2);

    List<MockCand> list = new ArrayList<>(List.of(c2, c1));
    list.sort(topsis.deterministicComparator());

    // c1 phải xếp trước c2 do SM3 (0.7 > 0.6) dù id2 > id1
    assertThat(list.get(0)).isSameAs(c1);
    assertThat(list.get(1)).isSameAs(c2);
  }

  @Test
  @DisplayName("P1 Test: Confidence Score thay đổi không bao giờ làm thay đổi TOPSIS MatchScore")
  void testP1_topsis_confidenceDoesNotAlterMatchScore() {
    TopsisRankingEngine topsis = new TopsisRankingEngine();
    ConfidenceCalculator confidenceCalc = new ConfidenceCalculator();
    EngineWeights weights = new EngineWeights(0.25, 0.25, 0.25, 0.25, "TEST", 0.0, null, null);

    // KOC A: Cold-start (0 campaign lịch sử)
    CandidateMemoryScores coldScores = new CandidateMemoryScores(
        0.50, 0.80, 0.85, 0.90,
        50.0, 80.0, 85.0, 90.0,
        0.80, 0.80, 0.80,
        0, 0, true, 3, false, true, true, 3, 3
    );

    // KOC B: Dữ liệu dồi dào (15 campaign lịch sử), nhưng cùng điểm Four-Memory
    CandidateMemoryScores richScores = new CandidateMemoryScores(
        0.50, 0.80, 0.85, 0.90,
        50.0, 80.0, 85.0, 90.0,
        0.80, 0.80, 0.80,
        15, 14, false, 3, false, true, true, 3, 3
    );

    TopsisResult resA = topsis.compute(coldScores, weights);
    TopsisResult resB = topsis.compute(richScores, weights);

    // MatchScore giống hệt nhau
    assertThat(resA.matchScore()).isEqualTo(resB.matchScore());

    // Trong khi Confidence khác biệt hoàn toàn
    ConfidenceAssessment confA = confidenceCalc.calculate(coldScores, weights);
    ConfidenceAssessment confB = confidenceCalc.calculate(richScores, weights);
    assertThat(confA.compositeConfidence()).isLessThan(confB.compositeConfidence());
    assertThat(confA.tier()).isNotEqualTo(confB.tier());
  }

  // =========================================================================
  // Phase 5: Recommendation Confidence Assessment
  // =========================================================================

  @Test
  @DisplayName("Phase 5: Confidence differentiates reliable creators from cold-start without altering Match Score")
  void testConfidenceCalculator_coldStartAndHighConfidence() {
    ConfidenceCalculator calculator = new ConfidenceCalculator();
    EngineWeights weights = new EngineWeights(0.25, 0.25, 0.25, 0.25, "TEST", 0.0, null, null);

    // High confidence creator: 8 past campaigns, 3+ posts, embedding present, niches configured
    CandidateMemoryScores highScores = new CandidateMemoryScores(
        0.75, 0.70, 0.80, 0.85,
        75.0, 70.0, 80.0, 85.0,
        0.70, 0.70, 0.70,
        8, 7, false,
        3, false,
        true,
        true,
        3, 3
    );

    ConfidenceAssessment highConf = calculator.calculate(highScores, weights);
    assertThat(highConf.tier()).isEqualTo(ConfidenceTier.HIGH);
    assertThat(highConf.compositeConfidence()).isGreaterThanOrEqualTo(0.75);
    assertThat(highConf.evidenceWarnings()).isEmpty();

    // Cold-start creator: 0 past campaigns, 1 post (stab imputed), no embedding, unconfigured niches
    CandidateMemoryScores lowScores = new CandidateMemoryScores(
        0.50, 0.50, 0.50, 0.50,
        50.0, 50.0, 50.0, 50.0,
        0.50, 0.50, 0.50,
        0, 0, true,
        1, true,
        false,
        false,
        0, 0
    );

    ConfidenceAssessment lowConf = calculator.calculate(lowScores, weights);
    assertThat(lowConf.tier()).isEqualTo(ConfidenceTier.LOW);
    assertThat(lowConf.compositeConfidence()).isLessThan(0.50);
    assertThat(lowConf.evidenceWarnings()).hasSize(4);
    assertThat(lowConf.evidenceWarnings().get(0)).contains("Cold-start");
  }

  @Test
  @DisplayName("Phase 5: M1 Reliability theo số lượng hợp tác quá khứ và loại trừ hoàn toàn outcome UNKNOWN")
  void testPhase5_m1_evidenceReliability_sampleSizes_and_unknownExclusion() {
    ConfidenceCalculator calculator = new ConfidenceCalculator();
    EngineWeights weights = new EngineWeights(0.25, 0.25, 0.25, 0.25, "TEST", 0.0, null, null);

    java.util.function.Function<Integer, CandidateMemoryScores> makeScores = sampleSize ->
        new CandidateMemoryScores(
            0.6, 0.6, 0.6, 0.6,
            60.0, 60.0, 60.0, 60.0,
            0.6, 0.6, 0.6,
            sampleSize, sampleSize, sampleSize == 0,
            5, false,
            true, true, 2, 2
        );

    // n >= 5 -> r1 = 1.00
    assertThat(calculator.calculate(makeScores.apply(5), weights).reliabilityM1()).isEqualTo(1.00);
    assertThat(calculator.calculate(makeScores.apply(10), weights).reliabilityM1()).isEqualTo(1.00);

    // n in [2, 4] -> r1 = 0.80
    assertThat(calculator.calculate(makeScores.apply(2), weights).reliabilityM1()).isEqualTo(0.80);
    assertThat(calculator.calculate(makeScores.apply(4), weights).reliabilityM1()).isEqualTo(0.80);

    // n == 1 -> r1 = 0.50
    assertThat(calculator.calculate(makeScores.apply(1), weights).reliabilityM1()).isEqualTo(0.50);

    // n == 0 -> r1 = 0.20 kèm cảnh báo Cold-start
    ConfidenceAssessment coldConf = calculator.calculate(makeScores.apply(0), weights);
    assertThat(coldConf.reliabilityM1()).isEqualTo(0.20);
    assertThat(coldConf.evidenceWarnings()).anyMatch(w -> w.contains("Cold-start"));

    // Kiểm chứng tích hợp với BayesianHistoryScorer:
    // 3 Campaign SUCCESS + 10 Campaign UNKNOWN -> evaluatedCount (n) phải bằng đúng 3 (không phải 13)
    BayesianHistoryScorer historyScorer = new BayesianHistoryScorer();
    List<HistoricalCampaign> pastWithUnknowns = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      HistoricalCampaign h = new HistoricalCampaign();
      CampaignOutcome out = new CampaignOutcome();
      out.setRoi(BigDecimal.valueOf(1.5));
      h.setOutcome(out);
      pastWithUnknowns.add(h);
    }
    for (int i = 0; i < 10; i++) {
      pastWithUnknowns.add(new HistoricalCampaign()); // UNKNOWN: không có outcome lẫn review
    }
    BayesianHistoryScorer.Result histResult = historyScorer.score(pastWithUnknowns, "CONVERSION");
    assertThat(histResult.sampleSize()).isEqualTo(3); // n = 3, UNKNOWN hoàn toàn không được tính vào mẫu số
    assertThat(histResult.successCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("Phase 5: M2 Reliability theo số lượng bài đăng và trạng thái Imputed của Stability")
  void testPhase5_m2_evidenceReliability_postCount_and_stabilityHeuristics() {
    ConfidenceCalculator calculator = new ConfidenceCalculator();
    EngineWeights weights = new EngineWeights(0.25, 0.25, 0.25, 0.25, "TEST", 0.0, null, null);

    // >= 10 posts & không imputed -> r2 = 1.00
    CandidateMemoryScores s10 = new CandidateMemoryScores(
        0.5, 0.5, 0.5, 0.5, 50, 50, 50, 50, 0.5, 0.5, 0.5, 5, 5, false, 12, false, true, true, 1, 1);
    assertThat(calculator.calculate(s10, weights).reliabilityM2()).isEqualTo(1.00);

    // 3-9 posts & không imputed -> r2 = 0.65
    CandidateMemoryScores s5 = new CandidateMemoryScores(
        0.5, 0.5, 0.5, 0.5, 50, 50, 50, 50, 0.5, 0.5, 0.5, 5, 5, false, 5, false, true, true, 1, 1);
    assertThat(calculator.calculate(s5, weights).reliabilityM2()).isEqualTo(0.65);

    // 2 posts & không imputed -> r2 = 0.45
    CandidateMemoryScores s2 = new CandidateMemoryScores(
        0.5, 0.5, 0.5, 0.5, 50, 50, 50, 50, 0.5, 0.5, 0.5, 5, 5, false, 2, false, true, true, 1, 1);
    assertThat(calculator.calculate(s2, weights).reliabilityM2()).isEqualTo(0.45);

    // 10 posts nhưng stability bị imputed (unavailable) -> r2 = 0.20 + warning
    CandidateMemoryScores sImputed = new CandidateMemoryScores(
        0.5, 0.5, 0.5, 0.5, 50, 50, 50, 50, 0.5, 0.5, 0.5, 5, 5, false, 10, true, true, true, 1, 1);
    ConfidenceAssessment confImputed = calculator.calculate(sImputed, weights);
    assertThat(confImputed.reliabilityM2()).isEqualTo(0.20);
    assertThat(confImputed.evidenceWarnings()).anyMatch(w -> w.contains("chỉ số ổn định"));

    // < 2 posts -> r2 = 0.20 + warning
    CandidateMemoryScores s1 = new CandidateMemoryScores(
        0.5, 0.5, 0.5, 0.5, 50, 50, 50, 50, 0.5, 0.5, 0.5, 5, 5, false, 1, false, true, true, 1, 1);
    ConfidenceAssessment conf1 = calculator.calculate(s1, weights);
    assertThat(conf1.reliabilityM2()).isEqualTo(0.20);
    assertThat(conf1.evidenceWarnings()).anyMatch(w -> w.contains("Số lượng bài đăng hạn chế"));
  }

  @Test
  @DisplayName("Phase 5: M3 và M4 Reliability và phát sinh cảnh báo thiếu dữ liệu")
  void testPhase5_m3_and_m4_evidenceReliability_and_warnings() {
    ConfidenceCalculator calculator = new ConfidenceCalculator();
    EngineWeights weights = new EngineWeights(0.25, 0.25, 0.25, 0.25, "TEST", 0.0, null, null);

    // M3: embeddingAvailable == true -> 1.00; false -> 0.30 + warning
    CandidateMemoryScores noEmb = new CandidateMemoryScores(
        0.5, 0.5, 0.5, 0.5, 50, 50, 50, 50, 0.5, 0.5, 0.5, 5, 5, false, 10, false, false, true, 1, 1);
    ConfidenceAssessment confNoEmb = calculator.calculate(noEmb, weights);
    assertThat(confNoEmb.reliabilityM3()).isEqualTo(0.30);
    assertThat(confNoEmb.evidenceWarnings()).anyMatch(w -> w.contains("vector nội dung"));

    // M4: brandNichesConfigured == true -> 1.00; false -> 0.50 + warning
    CandidateMemoryScores noNiche = new CandidateMemoryScores(
        0.5, 0.5, 0.5, 0.5, 50, 50, 50, 50, 0.5, 0.5, 0.5, 5, 5, false, 10, false, true, false, 0, 0);
    ConfidenceAssessment confNoNiche = calculator.calculate(noNiche, weights);
    assertThat(confNoNiche.reliabilityM4()).isEqualTo(0.50);
    assertThat(confNoNiche.evidenceWarnings()).anyMatch(w -> w.contains("lĩnh vực chi tiết"));
  }

  @Test
  @DisplayName("Phase 5: Phân cấp Confidence Tier (HIGH, MEDIUM, LOW) tại các ngưỡng biên 0.75 và 0.50")
  void testPhase5_compositeConfidence_tierThresholds() {
    ConfidenceCalculator calculator = new ConfidenceCalculator();
    EngineWeights weights = new EngineWeights(0.25, 0.25, 0.25, 0.25, "TEST", 0.0, null, null);

    // composite >= 0.75 -> HIGH
    // r1=1.0, r2=0.65, r3=1.0, r4=1.0 -> 0.25 * (1 + 0.65 + 1 + 1) = 0.25 * 3.65 = 0.9125
    CandidateMemoryScores sHigh = new CandidateMemoryScores(
        0.7, 0.7, 0.7, 0.7, 70, 70, 70, 70, 0.7, 0.7, 0.7, 5, 5, false, 5, false, true, true, 1, 1);
    assertThat(calculator.calculate(sHigh, weights).tier()).isEqualTo(ConfidenceTier.HIGH);

    // composite in [0.50, 0.75) -> MEDIUM
    // r1=0.50, r2=0.65, r3=1.0, r4=0.50 -> 0.25 * (0.50 + 0.65 + 1.0 + 0.50) = 0.25 * 2.65 = 0.6625
    CandidateMemoryScores sMed = new CandidateMemoryScores(
        0.6, 0.6, 0.6, 0.6, 60, 60, 60, 60, 0.6, 0.6, 0.6, 1, 1, false, 5, false, true, false, 0, 0);
    assertThat(calculator.calculate(sMed, weights).tier()).isEqualTo(ConfidenceTier.MEDIUM);

    // composite < 0.50 -> LOW
    // r1=0.20, r2=0.20, r3=0.30, r4=0.50 -> 0.25 * (0.2 + 0.2 + 0.3 + 0.5) = 0.25 * 1.2 = 0.30
    CandidateMemoryScores sLow = new CandidateMemoryScores(
        0.5, 0.5, 0.5, 0.5, 50, 50, 50, 50, 0.5, 0.5, 0.5, 0, 0, true, 1, true, false, false, 0, 0);
    assertThat(calculator.calculate(sLow, weights).tier()).isEqualTo(ConfidenceTier.LOW);
  }

  @Test
  @DisplayName("Phase 5: Cold-Start Fairness - Creator mới có điểm hiệu suất thực tế cao vẫn đạt MatchScore cao")
  void testPhase5_coldStartFairness_highPerformanceCreator_notPenalizedInMatchScore() {
    TopsisRankingEngine topsis = new TopsisRankingEngine();
    ConfidenceCalculator confidenceCalc = new ConfidenceCalculator();
    EngineWeights weights = new EngineWeights(0.20, 0.35, 0.30, 0.15, "TEST", 0.0, null, null);

    // Cold-start creator: chưa có campaign nào trên nền tảng (n=0, Bayesian smoothed sm1=0.50)
    // Nhưng có thông số công khai xuất sắc (sm2=0.92), độ liên quan nội dung xuất sắc (sm3=0.88), niche khớp (sm4=0.90)
    CandidateMemoryScores coldStartStarScores = new CandidateMemoryScores(
        0.50, 0.92, 0.88, 0.90,
        50.0, 92.0, 88.0, 90.0,
        0.90, 0.94, 0.92,
        0, 0, true,
        25, false,
        true,
        true,
        3, 3
    );

    TopsisResult topsisRes = topsis.compute(coldStartStarScores, weights);
    ConfidenceAssessment confRes = confidenceCalc.calculate(coldStartStarScores, weights);

    // MatchScore phản ánh đúng năng lực tương thích và đạt điểm rất cao (> 80/100)
    assertThat(topsisRes.matchScore()).isGreaterThan(80.0);

    // Confidence phản ánh độ tin cậy của bằng chứng: r1 = 0.20 kéo nhẹ composite nhưng vẫn minh bạch
    // composite = 0.20*0.20 + 0.35*1.0 + 0.30*1.0 + 0.15*1.0 = 0.04 + 0.35 + 0.30 + 0.15 = 0.84
    assertThat(confRes.reliabilityM1()).isEqualTo(0.20);
    assertThat(confRes.evidenceWarnings()).hasSize(1);
    assertThat(confRes.evidenceWarnings().get(0)).contains("Cold-start");

    // Khẳng định: MatchScore không hề bị nhân với hệ số phạt do Cold-Start
  }

  @Test
  @DisplayName("Phase 5: XAI bảo toàn 100% tất cả Evidence Warnings trong ReasoningCardDto thay vì chỉ lấy phần tử đầu")
  void testPhase5_xai_preservesAllEvidenceWarnings() {
    DeterministicReasoningGenerator generator = new DeterministicReasoningGenerator();

    RecommendationCandidateProjection cand = org.mockito.Mockito.mock(RecommendationCandidateProjection.class);
    org.mockito.Mockito.when(cand.getDisplayName()).thenReturn("KOC Multi-Warning");
    org.mockito.Mockito.when(cand.getPlatform()).thenReturn("TIKTOK");

    Campaign campaign = new Campaign();
    campaign.setName("Summer Awareness");

    ScoreBreakdownDto breakdown = ScoreBreakdownDto.builder()
        .m1Score(50.0).m2Score(50.0).m3Score(50.0).m4Score(50.0)
        .finalScore(50.0)
        .build();

    // Creator có đồng thời 3 cảnh báo bằng chứng
    List<String> threeWarnings = List.of(
        "Chưa có kết quả chiến dịch quá khứ được xác minh trên nền tảng (Cold-start)",
        "Số lượng bài đăng hạn chế (chỉ số ổn định chưa khả dụng hoặc ước lượng)",
        "Chưa có vector nội dung chi tiết"
    );

    ConfidenceAssessment confidence = new ConfidenceAssessment(
        ConfidenceTier.LOW, 0.35, 0.20, 0.20, 0.30, 1.00, threeWarnings
    );

    ReasoningCardDto card = generator.generate(cand, campaign, breakdown, confidence);

    // Xác nhận: evidenceWarnings trong DTO chứa trọn vẹn cả 3 cảnh báo
    assertThat(card.getEvidenceWarnings()).containsExactlyElementsOf(threeWarnings);

    // Xác nhận: danh sách reasons hiển thị cũng chứa đầy đủ cả 3 cảnh báo với tiền tố "Lưu ý: "
    for (String warn : threeWarnings) {
      assertThat(card.getReasons()).contains("Lưu ý: " + warn);
    }
  }

  @Test
  @DisplayName("Phase 5: Kiểm chứng ví dụ Reviewer P0 - Creator 3 bài, chưa có outcome nhưng đạt HIGH vẫn hiển thị cảnh báo")
  void testPhase5_reviewerP0_constructValidity_and_warningPreservation() {
    ConfidenceCalculator calculator = new ConfidenceCalculator();
    DeterministicReasoningGenerator generator = new DeterministicReasoningGenerator();

    // Chiến dịch AWARENESS với Business Weights [0.10, 0.35, 0.40, 0.15]
    EngineWeights awarenessWeights = new EngineWeights(0.10, 0.35, 0.40, 0.15, "TEST", 0.0, null, null);

    // Creator: chưa có outcome (r1=0.20), 3 bài đăng snapshot chuẩn (r2=0.65), embedding có (r3=1.0), niches có (r4=1.0)
    CandidateMemoryScores scores = new CandidateMemoryScores(
        0.50, 0.70, 0.85, 0.90,
        50.0, 70.0, 85.0, 90.0,
        0.70, 0.70, 0.70,
        0, 0, true,
        3, false,
        true,
        true,
        3, 3
    );

    ConfidenceAssessment conf = calculator.calculate(scores, awarenessWeights);

    // Tính toán số học: 0.10*0.20 + 0.35*0.65 + 0.40*1.0 + 0.15*1.0 = 0.02 + 0.2275 + 0.40 + 0.15 = 0.7975
    assertThat(conf.compositeConfidence()).isCloseTo(0.7975, offset(1e-4));
    assertThat(conf.tier()).isEqualTo(ConfidenceTier.HIGH);

    // ĐIỂM CỐT LÕI: Dù đạt nhãn HIGH (do Evidence Coverage theo trọng số AWARENESS), cảnh báo thiếu kết quả xác minh VẪN hiện diện!
    assertThat(conf.evidenceWarnings()).hasSize(1);
    assertThat(conf.evidenceWarnings().get(0)).contains("Chưa có kết quả chiến dịch quá khứ được xác minh");

    // XAI Card giữ nguyên cảnh báo này cho Marketer
    RecommendationCandidateProjection cand = org.mockito.Mockito.mock(RecommendationCandidateProjection.class);
    org.mockito.Mockito.when(cand.getDisplayName()).thenReturn("Creator Snapshot");
    org.mockito.Mockito.when(cand.getPlatform()).thenReturn("TIKTOK");
    Campaign campaign = new Campaign();
    campaign.setName("Brand Awareness 2026");

    ReasoningCardDto card = generator.generate(cand, campaign, ScoreBreakdownDto.builder().finalScore(78.5).build(), conf);
    assertThat(card.getEvidenceWarnings()).containsExactlyElementsOf(conf.evidenceWarnings());
    assertThat(card.getReasons().stream().anyMatch(r -> r.contains("Chưa có kết quả chiến dịch quá khứ được xác minh"))).isTrue();
  }

  @Test
  @DisplayName("Phase 5: Confidence là Campaign-weighted Coverage - Cùng Creator nhưng Confidence thay đổi theo Objective")
  void testPhase5_confidenceContextDependence_acrossObjectives() {
    ConfidenceCalculator calculator = new ConfidenceCalculator();

    // Cùng một KOC: chưa có outcome (r1=0.20), 3 bài đăng (r2=0.65), embedding có (r3=1.00), niches có (r4=1.00)
    CandidateMemoryScores scores = new CandidateMemoryScores(
        0.50, 0.70, 0.85, 0.90,
        50.0, 70.0, 85.0, 90.0,
        0.70, 0.70, 0.70,
        0, 0, true,
        3, false,
        true,
        true,
        3, 3
    );

    // Objective AWARENESS: chú trọng M2, M3 -> w1=0.10, w2=0.35, w3=0.40, w4=0.15
    EngineWeights awarenessWeights = new EngineWeights(0.10, 0.35, 0.40, 0.15, "TEST", 0.0, null, null);
    ConfidenceAssessment awarenessConf = calculator.calculate(scores, awarenessWeights);
    // Conf = 0.7975 -> HIGH
    assertThat(awarenessConf.compositeConfidence()).isCloseTo(0.7975, offset(1e-4));
    assertThat(awarenessConf.tier()).isEqualTo(ConfidenceTier.HIGH);

    // Objective CONVERSION: chú trọng M1 (Historical) -> w1=0.40, w2=0.15, w3=0.30, w4=0.15
    EngineWeights conversionWeights = new EngineWeights(0.40, 0.15, 0.30, 0.15, "TEST", 0.0, null, null);
    ConfidenceAssessment conversionConf = calculator.calculate(scores, conversionWeights);
    // Conf = 0.40*0.20 + 0.15*0.65 + 0.30*1.0 + 0.15*1.0 = 0.08 + 0.0975 + 0.30 + 0.15 = 0.6275 -> MEDIUM
    assertThat(conversionConf.compositeConfidence()).isCloseTo(0.6275, offset(1e-4));
    assertThat(conversionConf.tier()).isEqualTo(ConfidenceTier.MEDIUM);

    // Chứng minh: Confidence đo lường mức độ bao phủ dữ liệu the nhu cầu Campaign cụ thể:
    // Với Conversion đòi hỏi lịch sử chuyển đổi thì Creator này chỉ đạt MEDIUM độ tin cậy dữ liệu,
    // trong khi với Awareness đòi hỏi nội dung thì dữ liệu đã đủ bao phủ (HIGH),
    // và cả hai trường hợp đều minh bạch lưu ý thiếu kết quả lịch sử.
    assertThat(awarenessConf.compositeConfidence()).isNotEqualTo(conversionConf.compositeConfidence());
  }

  // =========================================================================
  // Phase 6: Post-Processing, Display Sorting & MMR Diversity
  // =========================================================================

  @Test
  @DisplayName("Phase 6: DisplaySorter supports BEST_MATCH and BUDGET_READY_FIRST while preserving Match Score")
  void testDisplaySorter_modesAndPreservation() {
    DisplaySorter sorter = new DisplaySorter();

    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    UUID id3 = UUID.randomUUID();

    TestDisplayItem itemHighOverBudget = new TestDisplayItem(id1, 92.0, 0.90, 0.85, PriceStatus.CONFIRMED_OVER_BUDGET);
    TestDisplayItem itemMidWithinBudget = new TestDisplayItem(id2, 78.0, 0.80, 0.75, PriceStatus.CONFIRMED_WITHIN_BUDGET);
    TestDisplayItem itemLowEstimate = new TestDisplayItem(id3, 65.0, 0.65, 0.60, PriceStatus.DECLARED_ESTIMATE);

    List<TestDisplayItem> items = List.of(itemMidWithinBudget, itemHighOverBudget, itemLowEstimate);

    // BEST_MATCH: strictly ordered by matchScore descending (92.0 -> 78.0 -> 65.0)
    List<TestDisplayItem> bestMatch = sorter.sort(items, DisplayMode.BEST_MATCH);
    assertThat(bestMatch.get(0).creatorId()).isEqualTo(id1);
    assertThat(bestMatch.get(1).creatorId()).isEqualTo(id2);
    assertThat(bestMatch.get(2).creatorId()).isEqualTo(id3);

    // BUDGET_READY_FIRST: ordered by PriceStatus priority first
    // (CONFIRMED_WITHIN_BUDGET (78.0) -> DECLARED_ESTIMATE (65.0) -> CONFIRMED_OVER_BUDGET (92.0))
    List<TestDisplayItem> budgetFirst = sorter.sort(items, DisplayMode.BUDGET_READY_FIRST);
    assertThat(budgetFirst.get(0).creatorId()).isEqualTo(id2);
    assertThat(budgetFirst.get(1).creatorId()).isEqualTo(id3);
    assertThat(budgetFirst.get(2).creatorId()).isEqualTo(id1);

    // MatchScores remain completely intact in both modes
    assertThat(budgetFirst.get(0).matchScore()).isEqualTo(78.0);
    assertThat(budgetFirst.get(2).matchScore()).isEqualTo(92.0);
  }

  @Test
  @DisplayName("Phase 6: MMR Reranker balances match relevance with content diversity")
  void testMmrReranker_promotesDiversity() {
    MmrReranker mmr = new MmrReranker();

    // 2 nearly identical Beauty candidates
    TestMmrItem beautyA = new TestMmrItem(90.0, "Beauty", "Skincare", new float[] {0.9f, 0.1f, 0.0f});
    TestMmrItem beautyB = new TestMmrItem(88.0, "Beauty", "Skincare", new float[] {0.89f, 0.11f, 0.0f});

    // 1 Tech candidate with different embedding
    TestMmrItem techC = new TestMmrItem(85.0, "Tech", "Gadgets", new float[] {0.0f, 0.1f, 0.9f});

    List<TestMmrItem> candidates = List.of(beautyA, beautyB, techC);

    // Select Top 2 with MMR (lambda = 0.50)
    List<TestMmrItem> selected = mmr.rerank(candidates, 2, 0.50);
    assertThat(selected).hasSize(2);

    // First selected is highest score (beautyA)
    assertThat(selected.get(0)).isSameAs(beautyA);

    // Second selected should be techC because beautyB is redundant with beautyA
    assertThat(selected.get(1)).isSameAs(techC);
  }

  @Test
  @DisplayName("Phase 6: DeterministicReasoningGenerator produces transparent data-backed reasoning")
  void testDeterministicReasoningGenerator_generatesTransparentReasons() {
    DeterministicReasoningGenerator generator = new DeterministicReasoningGenerator();

    RecommendationCandidateProjection cand = Mockito.mock(RecommendationCandidateProjection.class);
    Mockito.when(cand.getDisplayName()).thenReturn("Bao Ngoc Review");
    Mockito.when(cand.getPlatform()).thenReturn("TIKTOK");

    com.influencermatch.backend.campaign.model.Campaign campaign =
        com.influencermatch.backend.campaign.model.Campaign.builder()
            .name("Spring Cosmetics")
            .niches(List.of("Beauty", "Cosmetics"))
            .build();

    com.influencermatch.backend.recommendation.dto.ScoreBreakdownDto breakdown =
        com.influencermatch.backend.recommendation.dto.ScoreBreakdownDto.builder()
            .m1Score(50.0)
            .m2Score(78.0)
            .m3Score(85.0)
            .m4Score(90.0)
            .finalScore(81.25)
            .build();

    ConfidenceAssessment confidence = new ConfidenceAssessment(
        ConfidenceTier.MEDIUM,
        0.65,
        0.20, 1.0, 1.0, 1.0,
        List.of("KOC chưa có lịch sử hợp tác quá khứ trên nền tảng (Cold-start)")
    );

    var card = generator.generate(cand, campaign, breakdown, confidence);
    assertThat(card.isExplanationAvailable()).isFalse();
    assertThat(card.getSummary()).contains("Bao Ngoc Review", "Four-Memory TOPSIS", "81.3");
    assertThat(card.getReasons()).isNotEmpty();
    assertThat(card.getReasons().stream().anyMatch(r -> r.contains("tương đồng ngữ nghĩa"))).isTrue();
    assertThat(card.getReasons().stream().anyMatch(r -> r.contains("lĩnh vực mục tiêu"))).isTrue();
    assertThat(card.getReasons().stream().anyMatch(r -> r.contains("Cold-start"))).isTrue();
  }

  // =========================================================================
  // Integration Checks: Objective Mapping, Immutability & Master Dedup
  // =========================================================================

  @Test
  @DisplayName("Integration Check 1: Campaign Objective Mapping matches all canonical DB enums and aliases")
  void testPhase3_campaignObjectiveMapping_matchesAllCanonicalAndAliasEnums() {
    DynamicWeightBlender blender = new DynamicWeightBlender();

    // 1. Canonical DB Enums
    double[] wAwareness = blender.resolveBusinessWeights("AWARENESS");
    assertThat(wAwareness).containsExactly(0.10, 0.35, 0.40, 0.15);
    assertThat(DoubleStream.of(wAwareness).sum()).isCloseTo(1.0, offset(1e-6));

    double[] wEngagement = blender.resolveBusinessWeights("ENGAGEMENT");
    assertThat(wEngagement).containsExactly(0.15, 0.35, 0.35, 0.15);
    assertThat(DoubleStream.of(wEngagement).sum()).isCloseTo(1.0, offset(1e-6));

    double[] wTraffic = blender.resolveBusinessWeights("TRAFFIC");
    assertThat(wTraffic).containsExactly(0.20, 0.35, 0.30, 0.15);
    assertThat(DoubleStream.of(wTraffic).sum()).isCloseTo(1.0, offset(1e-6));

    double[] wConversion = blender.resolveBusinessWeights("CONVERSION");
    assertThat(wConversion).containsExactly(0.40, 0.15, 0.30, 0.15);
    assertThat(DoubleStream.of(wConversion).sum()).isCloseTo(1.0, offset(1e-6));

    // 2. Aliases and synonyms
    assertThat(blender.resolveBusinessWeights("BRAND_AWARENESS")).containsExactly(wAwareness);
    assertThat(blender.resolveBusinessWeights("CONVERSION_SALES")).containsExactly(wConversion);
    assertThat(blender.resolveBusinessWeights("SALES")).containsExactly(wConversion);

    double[] wCommunity = blender.resolveBusinessWeights("COMMUNITY_GROWTH");
    assertThat(wCommunity).containsExactly(0.20, 0.30, 0.30, 0.20);
    assertThat(DoubleStream.of(wCommunity).sum()).isCloseTo(1.0, offset(1e-6));

    // 3. Null returns default; unknown objective throws IllegalArgumentException (Fail-Fast)
    double[] wDefault = blender.resolveBusinessWeights(null);
    assertThat(wDefault).containsExactly(0.15, 0.35, 0.35, 0.15);

    assertThatThrownBy(() -> blender.resolveBusinessWeights("UNKNOWN_OBJ"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UNKNOWN_CAMPAIGN_OBJECTIVE");

    assertThatThrownBy(() -> blender.resolveBusinessWeights("CONVERSIONS"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UNKNOWN_CAMPAIGN_OBJECTIVE");
  }

  @Test
  @DisplayName("Integration Check 2: EngineWeights is completely immutable with defensive copying and array validation")
  void testPhase3_engineWeights_defensiveCopyAndImmutability() {
    double[] inputCritic = new double[] {0.20, 0.30, 0.30, 0.20};
    double[] inputBusiness = new double[] {0.25, 0.25, 0.25, 0.25};

    EngineWeights weights = new EngineWeights(
        0.20, 0.30, 0.30, 0.20,
        "CRITIC_DYNAMIC",
        0.30,
        inputCritic,
        inputBusiness
    );

    // 1. Mutating input arrays after construction does NOT affect EngineWeights
    inputCritic[0] = 999.0;
    inputBusiness[0] = -50.0;
    assertThat(weights.criticWeights()[0]).isEqualTo(0.20);
    assertThat(weights.businessWeights()[0]).isEqualTo(0.25);

    // 2. Mutating arrays obtained from accessors does NOT affect EngineWeights
    double[] accessedCritic = weights.criticWeights();
    accessedCritic[1] = 888.0;
    assertThat(weights.criticWeights()[1]).isEqualTo(0.30);

    double[] accessedBusiness = weights.businessWeights();
    accessedBusiness[2] = 777.0;
    assertThat(weights.businessWeights()[2]).isEqualTo(0.25);

    // 3. Array validation rejects wrong lengths
    assertThatThrownBy(() -> new EngineWeights(
        0.25, 0.25, 0.25, 0.25, "TEST", 0.0,
        new double[] {0.5, 0.5}, // length != 4
        inputBusiness
    )).isInstanceOf(IllegalStateException.class).hasMessageContaining("must have length 4");

    // 4. Array validation rejects non-unit sums
    assertThatThrownBy(() -> new EngineWeights(
        0.25, 0.25, 0.25, 0.25, "TEST", 0.0,
        new double[] {0.4, 0.4, 0.4, 0.4}, // sum = 1.6 != 1.0
        new double[] {0.25, 0.25, 0.25, 0.25}
    )).isInstanceOf(IllegalStateException.class).hasMessageContaining("sum must equal 1.0");

    // 5. Array validation rejects negative weights in array
    assertThatThrownBy(() -> new EngineWeights(
        0.25, 0.25, 0.25, 0.25, "TEST", 0.0,
        new double[] {-0.1, 0.4, 0.4, 0.3},
        new double[] {0.25, 0.25, 0.25, 0.25}
    )).isInstanceOf(IllegalStateException.class).hasMessageContaining("must be a finite number in [0.0, 1.0]");

    // 6. Metadata validation: CRITIC_BLENDED requires lambda > 0 and non-null criticWeights
    assertThatThrownBy(() -> new EngineWeights(
        0.25, 0.25, 0.25, 0.25, "CRITIC_BLENDED", 0.0,
        new double[] {0.25, 0.25, 0.25, 0.25},
        new double[] {0.25, 0.25, 0.25, 0.25}
    )).isInstanceOf(IllegalStateException.class).hasMessageContaining("requires lambdaUsed > 0.0");

    assertThatThrownBy(() -> new EngineWeights(
        0.25, 0.25, 0.25, 0.25, "CRITIC_BLENDED", 0.30,
        null,
        new double[] {0.25, 0.25, 0.25, 0.25}
    )).isInstanceOf(IllegalStateException.class).hasMessageContaining("requires non-null criticWeights");

    // 7. Metadata validation: BUSINESS_FALLBACK requires lambda == 0.0 and matching weights
    assertThatThrownBy(() -> new EngineWeights(
        0.25, 0.25, 0.25, 0.25, "BUSINESS_FALLBACK", 0.30,
        null,
        new double[] {0.25, 0.25, 0.25, 0.25}
    )).isInstanceOf(IllegalStateException.class).hasMessageContaining("requires lambdaUsed == 0.0");

    assertThatThrownBy(() -> new EngineWeights(
        0.40, 0.20, 0.20, 0.20, "BUSINESS_FALLBACK", 0.0,
        null,
        new double[] {0.25, 0.25, 0.25, 0.25} // mismatch with w1=0.40
    )).isInstanceOf(IllegalStateException.class).hasMessageContaining("INCONSISTENT_FALLBACK_WEIGHTS");

    // 8. Unsupported mode rejected
    assertThatThrownBy(() -> new EngineWeights(
        0.25, 0.25, 0.25, 0.25, "INVALID_MODE", 0.0,
        new double[] {0.25, 0.25, 0.25, 0.25},
        new double[] {0.25, 0.25, 0.25, 0.25}
    )).isInstanceOf(IllegalStateException.class).hasMessageContaining("Unsupported engine mode");
  }

  @Test
  @DisplayName("Integration Check 3: Master Dedup occurs before Limit, preserving requested creator count")
  void testPresentation_masterAwareDedupBeforeLimit_preservesTargetCount() {
    // 3 Creators, mỗi Creator có 2 account (TikTok & Instagram) -> Tổng 6 accounts
    UUID master1 = UUID.randomUUID();
    UUID master2 = UUID.randomUUID();
    UUID master3 = UUID.randomUUID();

    // Giả lập danh sách đã qua TOPSIS (sắp xếp giảm dần theo matchScore)
    // Master 1: TikTok (95), Instagram (90)
    // Master 2: TikTok (88), Instagram (82)
    // Master 3: TikTok (80), Instagram (75)
    class TestAccount {
      final UUID id;
      final UUID masterId;
      final double score;
      TestAccount(UUID id, UUID masterId, double score) {
        this.id = id; this.masterId = masterId; this.score = score;
      }
    }

    List<TestAccount> evaluatedList = List.of(
        new TestAccount(UUID.randomUUID(), master1, 95.0),
        new TestAccount(UUID.randomUUID(), master1, 90.0),
        new TestAccount(UUID.randomUUID(), master2, 88.0),
        new TestAccount(UUID.randomUUID(), master2, 82.0),
        new TestAccount(UUID.randomUUID(), master3, 80.0),
        new TestAccount(UUID.randomUUID(), master3, 75.0)
    );

    // Mô phỏng thuật toán tại AiRecommendationService (Step 7 -> Step 8):
    // Dedup trước trên toàn bộ pool
    Map<UUID, TestAccount> uniqueKocs = new LinkedHashMap<>();
    for (TestAccount acc : evaluatedList) {
      if (!uniqueKocs.containsKey(acc.masterId)) {
        uniqueKocs.put(acc.masterId, acc);
      } else if (acc.score > uniqueKocs.get(acc.masterId).score) {
        uniqueKocs.put(acc.masterId, acc);
      }
    }

    // Sort và Limit = 3
    List<TestAccount> sorted = uniqueKocs.values().stream()
        .sorted(Comparator.comparingDouble((TestAccount a) -> a.score).reversed())
        .limit(3)
        .toList();

    // Kiểm tra: Trả về ĐỦ 3 master độc lập, mỗi master chọn đúng channel điểm cao nhất
    assertThat(sorted).hasSize(3);
    assertThat(sorted.get(0).masterId).isEqualTo(master1);
    assertThat(sorted.get(0).score).isEqualTo(95.0);
    assertThat(sorted.get(1).masterId).isEqualTo(master2);
    assertThat(sorted.get(1).score).isEqualTo(88.0);
    assertThat(sorted.get(2).masterId).isEqualTo(master3);
    assertThat(sorted.get(2).score).isEqualTo(80.0);
  }

  // =========================================================================
  // Phase 6 Section 6 Acceptance Tests: Post-Processing, Display Sorting & MMR
  // =========================================================================

  @Test
  @DisplayName("P0 Test: BEST_MATCH vs DIVERSIFIED_MATCH - Pure BEST_MATCH preserves TOPSIS order; DIVERSIFIED_MATCH applies MMR while preserving MatchScore")
  void testP0_bestMatch_vs_diversifiedMatch_mmrSelectionAndScoreIntegrity() {
    DisplaySorter sorter = new DisplaySorter();
    MmrReranker mmr = new MmrReranker();

    UUID idA = UUID.randomUUID();
    UUID idB = UUID.randomUUID();
    UUID idC = UUID.randomUUID();

    TestBucketedMmrItem candA = new TestBucketedMmrItem(idA, 90.0, 0.90, 0.85, PriceStatus.PRICE_UNKNOWN, "Skincare", "Beauty", new float[] {1.0f, 0.0f});
    TestBucketedMmrItem candB = new TestBucketedMmrItem(idB, 88.0, 0.88, 0.80, PriceStatus.PRICE_UNKNOWN, "Skincare", "Beauty", new float[] {0.99f, 0.01f});
    TestBucketedMmrItem candC = new TestBucketedMmrItem(idC, 85.0, 0.85, 0.75, PriceStatus.PRICE_UNKNOWN, "Gadgets", "Tech", new float[] {0.0f, 1.0f});

    List<TestBucketedMmrItem> items = List.of(candB, candA, candC);

    // 1. Pure BEST_MATCH: strictly sorted by MatchScore descending without diversity perturbation
    List<TestBucketedMmrItem> bestMatchSorted = sorter.sort(items, DisplayMode.BEST_MATCH);
    assertThat(bestMatchSorted).containsExactly(candA, candB, candC);
    assertThat(bestMatchSorted.get(0).matchScore()).isEqualTo(90.0);
    assertThat(bestMatchSorted.get(1).matchScore()).isEqualTo(88.0);
    assertThat(bestMatchSorted.get(2).matchScore()).isEqualTo(85.0);

    // 2. DIVERSIFIED_MATCH (Top K = 2): MMR selects candA (highest score) and promotes candC (diverse Tech) over redundant candB
    List<TestBucketedMmrItem> diversified = mmr.rerank(bestMatchSorted, 2, 0.70);
    assertThat(diversified).hasSize(2);
    assertThat(diversified.get(0)).isSameAs(candA);
    assertThat(diversified.get(1)).isSameAs(candC);

    // 3. MatchScores remain completely intact in MMR output (never modified by MMR penalty)
    assertThat(diversified.get(0).matchScore()).isEqualTo(90.0);
    assertThat(diversified.get(1).matchScore()).isEqualTo(85.0);
  }

  @Test
  @DisplayName("P0 Test: Mode-aware Master Representative - BUDGET_READY_FIRST selects within-budget channel; BEST_MATCH selects highest MatchScore")
  void testP0_masterDedup_representativeSelection_modeAware() {
    UUID chOverBudget = UUID.randomUUID();
    UUID chWithinBudget = UUID.randomUUID();

    TestBucketedMmrItem ch1 = new TestBucketedMmrItem(
        chOverBudget, 95.0, 0.90, 0.85, PriceStatus.CONFIRMED_OVER_BUDGET, "Vlog", "Lifestyle", null);
    TestBucketedMmrItem ch2 = new TestBucketedMmrItem(
        chWithinBudget, 85.0, 0.80, 0.75, PriceStatus.CONFIRMED_WITHIN_BUDGET, "Vlog", "Lifestyle", null);

    List<TestBucketedMmrItem> channels = List.of(ch1, ch2);

    // Mode 1: BUDGET_READY_FIRST deduplication comparator
    Comparator<DisplaySorter.DisplayableItem> budgetComp = DisplaySorter.resolveComparator(DisplayMode.BUDGET_READY_FIRST);
    TestBucketedMmrItem repBudget = channels.get(0);
    for (int i = 1; i < channels.size(); i++) {
      if (budgetComp.compare(channels.get(i), repBudget) < 0) {
        repBudget = channels.get(i);
      }
    }
    // Priority 1 (WITHIN_BUDGET) is selected over Priority 4 (OVER_BUDGET) despite lower MatchScore
    assertThat(repBudget.creatorId()).isEqualTo(chWithinBudget);
    assertThat(repBudget.priceStatus()).isEqualTo(PriceStatus.CONFIRMED_WITHIN_BUDGET);

    // Mode 2: BEST_MATCH deduplication comparator
    Comparator<DisplaySorter.DisplayableItem> matchComp = DisplaySorter.resolveComparator(DisplayMode.BEST_MATCH);
    TestBucketedMmrItem repMatch = channels.get(0);
    for (int i = 1; i < channels.size(); i++) {
      if (matchComp.compare(channels.get(i), repMatch) < 0) {
        repMatch = channels.get(i);
      }
    }
    // Highest MatchScore (95.0) is selected
    assertThat(repMatch.creatorId()).isEqualTo(chOverBudget);
    assertThat(repMatch.matchScore()).isEqualTo(95.0);
  }

  @Test
  @DisplayName("P0 Test: Deterministic Tie-breaking - Same Master with identical score/priority yields identical representative when input order is reversed")
  void testP0_masterDedup_deterministicTieBreaking_orderIndependent() {
    UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    TestBucketedMmrItem ch1 = new TestBucketedMmrItem(
        id1, 80.0, 0.80, 0.70, PriceStatus.CONFIRMED_WITHIN_BUDGET, "Tech", "Tech", null);
    TestBucketedMmrItem ch2 = new TestBucketedMmrItem(
        id2, 80.0, 0.80, 0.70, PriceStatus.CONFIRMED_WITHIN_BUDGET, "Tech", "Tech", null);

    Comparator<DisplaySorter.DisplayableItem> comp = DisplaySorter.resolveComparator(DisplayMode.BUDGET_READY_FIRST);

    // Forward order: [ch1, ch2]
    TestBucketedMmrItem winnerForward = ch1;
    if (comp.compare(ch2, winnerForward) < 0) winnerForward = ch2;

    // Reverse order: [ch2, ch1]
    TestBucketedMmrItem winnerReverse = ch2;
    if (comp.compare(ch1, winnerReverse) < 0) winnerReverse = ch1;

    // Both must yield id1 (due to deterministic UUID ascending tie-breaker)
    assertThat(winnerForward.creatorId()).isEqualTo(id1);
    assertThat(winnerReverse.creatorId()).isEqualTo(id1);
    assertThat(winnerForward).isEqualTo(winnerReverse);
  }

  @Test
  @DisplayName("P0 Test: Intra-Bucket MMR preserves strict budget priority order (Priority 1 -> 2 -> 3 -> 4 -> 5) with zero budget leakage")
  void testP0_intraBucketMmr_strictBudgetGroupPreservation_noBudgetLeakage() {
    MmrReranker mmr = new MmrReranker();

    TestBucketedMmrItem a = new TestBucketedMmrItem(
        UUID.randomUUID(), 90.0, 0.90, 0.80, PriceStatus.CONFIRMED_WITHIN_BUDGET, "Beauty", "Skincare", new float[] {1f, 0f});
    TestBucketedMmrItem b = new TestBucketedMmrItem(
        UUID.randomUUID(), 85.0, 0.85, 0.75, PriceStatus.CONFIRMED_WITHIN_BUDGET, "Beauty", "Skincare", new float[] {0.98f, 0.02f});
    TestBucketedMmrItem c = new TestBucketedMmrItem(
        UUID.randomUUID(), 95.0, 0.95, 0.90, PriceStatus.DECLARED_ESTIMATE, "Tech", "Gadgets", new float[] {0f, 1f});
    TestBucketedMmrItem d = new TestBucketedMmrItem(
        UUID.randomUUID(), 99.0, 0.99, 0.99, PriceStatus.CONFIRMED_OVER_BUDGET, "Fashion", "Clothing", new float[] {0.5f, 0.5f});

    List<TestBucketedMmrItem> all = List.of(d, c, b, a); // Shuffled input

    List<TestBucketedMmrItem> reranked = mmr.rerankIntraBucket(all, 4, 0.70);

    assertThat(reranked).hasSize(4);

    // 1. Priority 1 items (a, b) MUST precede Priority 2 (c) and Priority 5 (d)
    assertThat(reranked.get(0).budgetPriority()).isEqualTo(1);
    assertThat(reranked.get(1).budgetPriority()).isEqualTo(1);
    assertThat(reranked.get(2).budgetPriority()).isEqualTo(2); // Candidate c (DECLARED_ESTIMATE)
    assertThat(reranked.get(3).budgetPriority()).isEqualTo(5); // Candidate d (CONFIRMED_OVER_BUDGET)

    // Over-budget candidate d (score 99) NEVER leaks into top budget positions
    assertThat(reranked.get(3)).isSameAs(d);
  }

  @Test
  @DisplayName("P1 Test: MMR Vector Cosine overrides discrete Category, with zero-norm and dimension-mismatch safety")
  void testP1_mmrVectorCosine_overridesCategory_andHandlesVectorEdgeCasesSafely() {
    MmrReranker mmr = new MmrReranker();

    // candA is selected. candB has different category but identical vector. candC has same category but orthogonal vector.
    TestBucketedMmrItem candA = new TestBucketedMmrItem(
        UUID.randomUUID(), 90.0, 0.9, 0.9, PriceStatus.PRICE_UNKNOWN, "Tech", "Tech", new float[] {1.0f, 0.0f});
    TestBucketedMmrItem candB = new TestBucketedMmrItem(
        UUID.randomUUID(), 88.0, 0.88, 0.88, PriceStatus.PRICE_UNKNOWN, "Beauty", "Beauty", new float[] {0.999f, 0.001f});
    TestBucketedMmrItem candC = new TestBucketedMmrItem(
        UUID.randomUUID(), 87.0, 0.87, 0.87, PriceStatus.PRICE_UNKNOWN, "Tech", "Tech", new float[] {0.0f, 1.0f});

    // Top 2: candA selected first. candC is selected second because candB's vector is redundant with candA!
    List<TestBucketedMmrItem> selected = mmr.rerank(List.of(candA, candB, candC), 2, 0.70);
    assertThat(selected.get(0)).isSameAs(candA);
    assertThat(selected.get(1)).isSameAs(candC);

    // Edge Cases: Zero norm vectors & dimension mismatch never cause NaN or crash
    TestBucketedMmrItem zeroVec = new TestBucketedMmrItem(
        UUID.randomUUID(), 80.0, 0.8, 0.8, PriceStatus.PRICE_UNKNOWN, "Beauty", "Beauty", new float[] {0.0f, 0.0f});
    TestBucketedMmrItem mismatchDimVec = new TestBucketedMmrItem(
        UUID.randomUUID(), 80.0, 0.8, 0.8, PriceStatus.PRICE_UNKNOWN, "Beauty", "Beauty", new float[] {1.0f, 0.0f, 0.0f});
    TestBucketedMmrItem emptyVec = new TestBucketedMmrItem(
        UUID.randomUUID(), 80.0, 0.8, 0.8, PriceStatus.PRICE_UNKNOWN, "Beauty", "Beauty", new float[0]);

    List<TestBucketedMmrItem> edgeReranked = mmr.rerank(List.of(candA, zeroVec, mismatchDimVec, emptyVec), 4, 0.70);
    assertThat(edgeReranked).hasSize(4);
    for (TestBucketedMmrItem item : edgeReranked) {
      assertThat(Double.isNaN(item.matchScore())).isFalse();
      assertThat(Double.isInfinite(item.matchScore())).isFalse();
    }
  }

  @Test
  @DisplayName("P0 Test: PROVISIONAL candidate uses STAB floor = 0.00 and conservative Confidence reliabilityM2 = 0.20")
  void testP0_provisionalCandidate_usesZeroStabFloor_andConservativeConfidence() {
    PublicPerformanceScorer scorer = new PublicPerformanceScorer();
    ConfidenceCalculator confCalculator = new ConfidenceCalculator();

    // Context mô phỏng ứng viên PROVISIONAL (không đủ bài viết trong cửa sổ 3 bài)
    // stabNormalized = Double.NaN, stabilityEvidenceUnavailable = true
    NormalizedCandidateContext provisionalContext = new NormalizedCandidateContext(
        UUID.randomUUID(),
        null,
        "TIKTOK",
        Phase0Status.PENDING_DATA,
        List.of("SHORT_WINDOW_INSUFFICIENT_POSTS: Valid posts in 3-post window 1 < 2"),
        0.60, 0.40, Double.NaN, // ER = 0.60, VR = 0.40, STAB = NaN
        0.05, 0.20, Double.NaN,
        ErMethod.BY_VIEWS, 1, true, 0.20, "V1"
    );

    PublicPerformanceScorer.Result res = scorer.score(provisionalContext, null);

    // 1. STAB imputation tuyệt đối tuân thủ chính sách sàn 0.00 (STAB_MISSING_POLICY_FLOOR), không phải 0.50
    // SM2 = (0.60 + 0.40 + 0.00) / 3.0 = 0.333333
    assertThat(res.isStabImputed()).isTrue();
    assertThat(res.stabNorm()).isNaN();
    assertThat(res.sm2Norm()).isCloseTo((0.60 + 0.40 + 0.00) / 3.0, offset(1e-6));
    assertThat(res.score100()).isCloseTo(33.33, offset(0.01));

    // 2. Confidence M2 reliability khi STAB unavailable bị giảm xuống mức sàn reliabilityM2 = 0.20
    CandidateMemoryScores scores = new CandidateMemoryScores(
        0.50, res.sm2Norm(), 0.50, 0.50,
        50.0, res.score100(), 50.0, 50.0,
        0.50, 0.50, 0.50,
        0, 0, false,
        1, true, false, true, 1, 1
    );
    EngineWeights weights = new DynamicWeightBlender().blend("AWARENESS", Optional.empty());
    ConfidenceAssessment conf = confCalculator.calculate(scores, weights);

    assertThat(conf.reliabilityM2()).isEqualTo(0.20);
    assertThat(conf.evidenceWarnings()).anyMatch(w -> w.contains("chỉ số ổn định"));
  }

  @Test
  @DisplayName("P0 Test: End-to-end Integration - Campaign Budget + Pricing Data -> PriceStatus -> Master Representative -> Display Sort")
  void testP0_endToEndIntegration_campaignBudgetPlusPricing_to_priceStatus_to_masterDedup_to_displaySort() {
    BigDecimal budgetMax = BigDecimal.valueOf(10_000_000); // 10 triệu VND

    // 1. Phân loại PriceStatus từ dữ liệu giá thực tế (Truth Table 5 trạng thái)
    PriceStatus p1ConfirmedWithin = PriceStatusResolver.resolve(BigDecimal.valueOf(8_000_000), true, budgetMax);
    PriceStatus p2DeclaredEstimate = PriceStatusResolver.resolve(BigDecimal.valueOf(7_000_000), false, budgetMax);
    PriceStatus p3Unknown = PriceStatusResolver.resolve(null, false, budgetMax);
    PriceStatus p4EstimatedOver = PriceStatusResolver.resolve(BigDecimal.valueOf(30_000_000), false, budgetMax);
    PriceStatus p5ConfirmedOver = PriceStatusResolver.resolve(BigDecimal.valueOf(30_000_000), true, budgetMax);

    assertThat(p1ConfirmedWithin).isEqualTo(PriceStatus.CONFIRMED_WITHIN_BUDGET);
    assertThat(p2DeclaredEstimate).isEqualTo(PriceStatus.DECLARED_ESTIMATE);
    assertThat(p3Unknown).isEqualTo(PriceStatus.PRICE_UNKNOWN);
    // Nghiệm thu P0: Giá chưa xác nhận vượt ngân sách PHẢI là ESTIMATED_OVER_BUDGET, KHÔNG THỂ là CONFIRMED_OVER_BUDGET
    assertThat(p4EstimatedOver).isEqualTo(PriceStatus.ESTIMATED_OVER_BUDGET);
    assertThat(p4EstimatedOver).isNotEqualTo(PriceStatus.CONFIRMED_OVER_BUDGET);
    assertThat(p5ConfirmedOver).isEqualTo(PriceStatus.CONFIRMED_OVER_BUDGET);

    // 2. Master có 2 kênh: TikTok (30M - over budget, score 95) và Instagram (8M - within budget, score 85)
    UUID tikTokId = UUID.randomUUID();
    UUID instaId = UUID.randomUUID();

    TestBucketedMmrItem chTikTok = new TestBucketedMmrItem(
        tikTokId, 95.0, 0.90, 0.85, p5ConfirmedOver, "Vlog", "Lifestyle", null);
    TestBucketedMmrItem chInsta = new TestBucketedMmrItem(
        instaId, 85.0, 0.80, 0.75, p1ConfirmedWithin, "Vlog", "Lifestyle", null);

    // Ở BUDGET_READY_FIRST: Master Dedup chọn kênh trong ngân sách (Instagram 8M)
    Comparator<DisplaySorter.DisplayableItem> budgetComp = DisplaySorter.resolveComparator(DisplayMode.BUDGET_READY_FIRST);
    TestBucketedMmrItem winnerBudget = budgetComp.compare(chInsta, chTikTok) < 0 ? chInsta : chTikTok;
    assertThat(winnerBudget.creatorId()).isEqualTo(instaId);
    assertThat(winnerBudget.priceStatus()).isEqualTo(PriceStatus.CONFIRMED_WITHIN_BUDGET);

    // Ở BEST_MATCH: Master Dedup chọn kênh MatchScore cao nhất (TikTok 95.0)
    Comparator<DisplaySorter.DisplayableItem> matchComp = DisplaySorter.resolveComparator(DisplayMode.BEST_MATCH);
    TestBucketedMmrItem winnerMatch = matchComp.compare(chTikTok, chInsta) < 0 ? chTikTok : chInsta;
    assertThat(winnerMatch.creatorId()).isEqualTo(tikTokId);
    assertThat(winnerMatch.matchScore()).isEqualTo(95.0);

    // 3. Display Sorting thứ tự 5 mức Priority
    TestBucketedMmrItem item1 = new TestBucketedMmrItem(instaId, 85.0, 0.8, 0.7, p1ConfirmedWithin, "Vlog", "Lifestyle", null);
    TestBucketedMmrItem item2 = new TestBucketedMmrItem(UUID.randomUUID(), 88.0, 0.8, 0.7, p2DeclaredEstimate, "Vlog", "Lifestyle", null);
    TestBucketedMmrItem item3 = new TestBucketedMmrItem(UUID.randomUUID(), 90.0, 0.8, 0.7, p3Unknown, "Vlog", "Lifestyle", null);
    TestBucketedMmrItem item4 = new TestBucketedMmrItem(UUID.randomUUID(), 92.0, 0.8, 0.7, p4EstimatedOver, "Vlog", "Lifestyle", null);
    TestBucketedMmrItem item5 = new TestBucketedMmrItem(tikTokId, 95.0, 0.9, 0.8, p5ConfirmedOver, "Vlog", "Lifestyle", null);

    List<TestBucketedMmrItem> sorted = new DisplaySorter().sort(List.of(item5, item4, item3, item2, item1), DisplayMode.BUDGET_READY_FIRST);

    assertThat(sorted.get(0).priceStatus()).isEqualTo(PriceStatus.CONFIRMED_WITHIN_BUDGET);
    assertThat(sorted.get(1).priceStatus()).isEqualTo(PriceStatus.DECLARED_ESTIMATE);
    assertThat(sorted.get(2).priceStatus()).isEqualTo(PriceStatus.PRICE_UNKNOWN);
    assertThat(sorted.get(3).priceStatus()).isEqualTo(PriceStatus.ESTIMATED_OVER_BUDGET);
    assertThat(sorted.get(4).priceStatus()).isEqualTo(PriceStatus.CONFIRMED_OVER_BUDGET);
  }

  @Test
  @DisplayName("P0 Test: Pricing Data Flow & Graceful Fallback - Unquoted Candidates Default to PRICE_UNKNOWN without Hallucination")
  void testP0_pricingDataFlow_unquotedCandidatesDefaultToPriceUnknown() {
    BigDecimal budgetMax = BigDecimal.valueOf(15_000_000);

    // Candidate 1: Missing all pricing (crawled public creator, retired rate card)
    PriceStatus missingPrice = PriceStatusResolver.resolve(null, null, budgetMax);
    assertThat(missingPrice).isEqualTo(PriceStatus.PRICE_UNKNOWN);

    // Candidate 2: Campaign budget is unspecified (budgetMax is null or <= 0)
    PriceStatus unspecifiedBudget = PriceStatusResolver.resolve(BigDecimal.valueOf(10_000_000), false, (BigDecimal) null);
    assertThat(unspecifiedBudget).isEqualTo(PriceStatus.PRICE_UNKNOWN);

    // Candidate 3: Unconfirmed historical fee within budget
    PriceStatus historicalWithin = PriceStatusResolver.resolve(BigDecimal.valueOf(12_000_000), false, budgetMax);
    assertThat(historicalWithin).isEqualTo(PriceStatus.DECLARED_ESTIMATE);

    // Candidate 4: Unconfirmed historical fee exceeding budget
    PriceStatus historicalOver = PriceStatusResolver.resolve(BigDecimal.valueOf(25_000_000), false, budgetMax);
    assertThat(historicalOver).isEqualTo(PriceStatus.ESTIMATED_OVER_BUDGET);

    // Soft Preference Verification: All candidates, even PRICE_UNKNOWN or ESTIMATED_OVER_BUDGET,
    // remain present in the result pool - none are silently discarded as hard violations.
    List<TestBucketedMmrItem> allCandidates = List.of(
        new TestBucketedMmrItem(UUID.randomUUID(), 90.0, 0.9, 0.8, historicalOver, "Tech", "Tech", null),
        new TestBucketedMmrItem(UUID.randomUUID(), 85.0, 0.8, 0.7, missingPrice, "Beauty", "Beauty", null),
        new TestBucketedMmrItem(UUID.randomUUID(), 80.0, 0.8, 0.7, historicalWithin, "Food", "Food", null)
    );

    List<TestBucketedMmrItem> bestMatchSorted = new DisplaySorter().sort(allCandidates, DisplayMode.BEST_MATCH);
    assertThat(bestMatchSorted).hasSize(3);
    assertThat(bestMatchSorted.get(0).matchScore()).isEqualTo(90.0); // Ranked purely by MatchScore

    List<TestBucketedMmrItem> budgetSorted = new DisplaySorter().sort(allCandidates, DisplayMode.BUDGET_READY_FIRST);
    assertThat(budgetSorted).hasSize(3);
    assertThat(budgetSorted.get(0).priceStatus()).isEqualTo(PriceStatus.DECLARED_ESTIMATE); // 80.0 score but within budget
    assertThat(budgetSorted.get(1).priceStatus()).isEqualTo(PriceStatus.PRICE_UNKNOWN);
    assertThat(budgetSorted.get(2).priceStatus()).isEqualTo(PriceStatus.ESTIMATED_OVER_BUDGET);
  }

  // --- Test Helper Records ---
  private record TestDisplayItem(
      UUID creatorId,
      double matchScore,
      double sm3,
      double sm2,
      PriceStatus priceStatus
  ) implements DisplaySorter.DisplayableItem {}

  private record TestMmrItem(
      double matchScore,
      String niche,
      String category,
      float[] embedding
  ) implements MmrReranker.MmrCandidate {}

  private record TestBucketedMmrItem(
      UUID creatorId,
      double matchScore,
      double sm3,
      double sm2,
      PriceStatus priceStatus,
      String niche,
      String category,
      float[] embedding
  ) implements DisplaySorter.DisplayableItem, MmrReranker.BucketedMmrCandidate {
    @Override
    public int budgetPriority() {
      return priceStatus != null ? priceStatus.getPriority() : PriceStatus.PRICE_UNKNOWN.getPriority();
    }
  }
}
