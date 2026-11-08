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
import com.influencermatch.backend.recommendation.engine.topsis.TopsisRankingEngine;
import com.influencermatch.backend.recommendation.engine.topsis.model.TopsisResult;
import java.math.BigDecimal;
import java.util.*;
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
  @DisplayName("P0 Test: Campaign lịch sử thiếu ROI -> Không tự chuyển Missing thành ROI = 0 hoặc Success")
  void testP0_historicalCampaign_missingRoiDoesNotInventFakeSuccessOrZero() {
    BayesianHistoryScorer scorer = new BayesianHistoryScorer();

    // 1 campaign có bản ghi nhưng thiếu outcome (hoặc outcome thiếu ROI và KPI)
    HistoricalCampaign missingHist = HistoricalCampaign.builder()
        .outcome(null)
        .evidenceValue(null)
        .build();

    BayesianHistoryScorer.Result res = scorer.score(List.of(missingHist), "CONVERSION");

    // n = 1, nhưng s = 0 (Không tự động biến thành 1 thành công giả tạo)
    assertThat(res.sampleSize()).isEqualTo(1);
    assertThat(res.successCount()).isEqualTo(0);
    // Posterior pHat = (0 + 3 * 0.5) / (1 + 3) = 1.5 / 4 = 0.375
    assertThat(res.pHat()).isEqualTo(0.375);
    // kpiScore giữ nguyên prior 0.50 (không bị ép về 0.0)
    assertThat(res.isColdStart()).isFalse();
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
    assertThat(card.isExplanationAvailable()).isTrue();
    assertThat(card.getSummary()).contains("Bao Ngoc Review", "Four-Memory TOPSIS", "81.3");
    assertThat(card.getReasons()).isNotEmpty();
    assertThat(card.getReasons().stream().anyMatch(r -> r.contains("tương đồng ngữ nghĩa"))).isTrue();
    assertThat(card.getReasons().stream().anyMatch(r -> r.contains("lĩnh vực mục tiêu"))).isTrue();
    assertThat(card.getReasons().stream().anyMatch(r -> r.contains("Cold-start"))).isTrue();
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
}
