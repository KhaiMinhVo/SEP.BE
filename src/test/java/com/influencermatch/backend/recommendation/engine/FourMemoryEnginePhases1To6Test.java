package com.influencermatch.backend.recommendation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.collaboration.model.CampaignOutcome;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.recommendation.engine.confidence.ConfidenceCalculator;
import com.influencermatch.backend.recommendation.engine.confidence.enums.ConfidenceTier;
import com.influencermatch.backend.recommendation.engine.confidence.model.ConfidenceAssessment;
import com.influencermatch.backend.recommendation.engine.critic.CriticWeightCalculator;
import com.influencermatch.backend.recommendation.engine.critic.DynamicWeightBlender;
import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import com.influencermatch.backend.recommendation.engine.memory.BayesianHistoryScorer;
import com.influencermatch.backend.recommendation.engine.memory.BrandAffinityScorer;
import com.influencermatch.backend.recommendation.engine.memory.PublicPerformanceScorer;
import com.influencermatch.backend.recommendation.engine.memory.SemanticRelevanceScorer;
import com.influencermatch.backend.recommendation.engine.memory.model.CandidateMemoryScores;
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
