package com.influencermatch.backend.recommendation.engine.phase0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.recommendation.engine.phase0.enums.CampaignEligibility;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import com.influencermatch.backend.recommendation.engine.phase0.enums.Phase0Status;
import com.influencermatch.backend.recommendation.engine.phase0.model.NormalizedCandidateContext;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Phase0EngineTest {

  private final EligibilityChecker eligibilityChecker = new EligibilityChecker();
  private final EvidenceValidator evidenceValidator = new EvidenceValidator();
  private final PolicyNormalizer policyNormalizer = new PolicyNormalizer();
  private final Phase0Orchestrator orchestrator = new Phase0Orchestrator();

  @Test
  @DisplayName("Luồng 1: Hard constraint nền tảng - Phù hợp thì PASS, lệch thì FAIL")
  void testEligibility_platformMatch() {
    Campaign campaign = Campaign.builder().platforms(List.of("TIKTOK")).build();

    RecommendationCandidateProjection tiktokCand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);
    var passResult = eligibilityChecker.check(tiktokCand, campaign, Collections.emptySet());
    assertThat(passResult.eligible()).isTrue();
    assertThat(passResult.reasons()).isEmpty();

    RecommendationCandidateProjection igCand = mockCandidate(UUID.randomUUID(), "INSTAGRAM", 60_000L);
    var failResult = eligibilityChecker.check(igCand, campaign, Collections.emptySet());
    assertThat(failResult.eligible()).isFalse();
    assertThat(failResult.reasons()).contains("PLATFORM_MISMATCH: INSTAGRAM");
  }

  @Test
  @DisplayName("Luồng 1: Soft preference follower - Vượt khoảng mong muốn KHÔNG bị loại")
  void testEligibility_softPreferencesNotExcluded() {
    Campaign campaign = Campaign.builder()
        .platforms(List.of("TIKTOK"))
        .followerMin(10_000L)
        .followerMax(100_000L)
        .build();

    // KOC có 150.000 followers (vượt trần 100k)
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "TIKTOK", 150_000L);
    var result = eligibilityChecker.check(cand, campaign, Collections.emptySet());

    assertThat(result.eligible()).isTrue();
    assertThat(result.outsideFollowers()).isTrue();
  }

  @Test
  @DisplayName("Luồng 2: Crawler 3 bài - m >= 2 tính được mẫu số độ ổn định, m < 2 chuyển PENDING_DATA")
  void testEvidence_stabilityCalculation() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);

    // KOC có 3 bài: 20k, 25k, 30k views -> mean = 25k
    Post p1 = Post.builder().views(20_000L).likes(1_000L).comments(100L).shares(50L).build();
    Post p2 = Post.builder().views(25_000L).likes(1_250L).comments(120L).shares(60L).build();
    Post p3 = Post.builder().views(30_000L).likes(1_500L).comments(150L).shares(70L).build();

    var metrics = evidenceValidator.evaluate(cand, List.of(p1, p2, p3));
    assertThat(metrics.canComputeStability()).isTrue();
    assertThat(metrics.isStabImputed()).isFalse();
    assertThat(metrics.rawStab()).isBetween(0.0, 1.0);
    assertThat(metrics.evidenceReliabilityM2()).isEqualTo(0.65); // Cửa sổ 3 bài (Short-Window)

    // KOC chỉ có 1 bài -> không thể tính độ lệch chuẩn mẫu s_V (nhưng ER và VR đầy đủ -> PROVISIONAL)
    Post singlePost = Post.builder().views(12_000L).likes(500L).build();
    var singleMetrics = evidenceValidator.evaluate(cand, List.of(singlePost));
    assertThat(singleMetrics.canComputeStability()).isFalse();
    assertThat(singleMetrics.isStabImputed()).isTrue();
    assertThat(singleMetrics.rawStab()).isNaN(); // Giữ Double.NaN, không gán số giả tùy tiện
    assertThat(singleMetrics.evidenceReliabilityM2()).isEqualTo(0.20);
    assertThat(singleMetrics.scoringReadiness())
        .isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.PROVISIONAL);
    assertThat(singleMetrics.dataQualityReasons()).anyMatch(r -> r.contains("SHORT_WINDOW_INSUFFICIENT_POSTS"));
  }

  @Test
  @DisplayName("Luồng 2: Fallback ER cho Facebook - Bài không views tính tương tác trung bình chia follower")
  void testEvidence_facebookFollowerErFallback() {
    // KOC Facebook 10.000 followers, có 2 bài không có views (ảnh/text), mỗi bài
    // 200 tương tác
    RecommendationCandidateProjection fbCand = mockCandidate(UUID.randomUUID(), "FACEBOOK", 10_000L);
    Post p1 = Post.builder().views(0L).likes(150L).comments(30L).shares(20L).build(); // 200 int
    Post p2 = Post.builder().views(0L).likes(160L).comments(25L).shares(15L).build(); // 200 int

    var metrics = evidenceValidator.evaluate(fbCand, List.of(p1, p2));
    assertThat(metrics.erMethod()).isEqualTo(ErMethod.BY_FOLLOWERS);
    // (400 / 2) / 10,000 = 200 / 10,000 = 0.02 (2.0%)
    assertThat(metrics.rawEr()).isEqualTo(0.02);
  }

  @Test
  @DisplayName("Luồng 3: Saturation Normalization x / (x + K) - Khi x = K thì S = 0.5")
  void testPolicyNormalization_halfSaturationAtK() {
    // TikTok: K_ER = 0.05
    double kErTikTok = policyNormalizer.resolveKEr("TIKTOK", ErMethod.BY_VIEWS);
    assertThat(kErTikTok).isEqualTo(0.05);
    double sTikTok = policyNormalizer.normalize(0.05, kErTikTok);
    assertThat(sTikTok).isEqualTo(0.50);

    // Instagram: K_ER = 0.04
    double kErIg = policyNormalizer.resolveKEr("INSTAGRAM", ErMethod.BY_VIEWS);
    assertThat(kErIg).isEqualTo(0.04);
    double sIg = policyNormalizer.normalize(0.04, kErIg);
    assertThat(sIg).isEqualTo(0.50);

    // Facebook: K_ER = 0.025
    double kErFb = policyNormalizer.resolveKEr("FACEBOOK", ErMethod.BY_VIEWS);
    assertThat(kErFb).isEqualTo(0.025);
    double sFb = policyNormalizer.normalize(0.025, kErFb);
    assertThat(sFb).isEqualTo(0.50);

    // Xử lý lỗi nghiêm ngặt: không che giấu lỗi bằng 0 hay 0.50
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> policyNormalizer.normalize(-0.01, 0.05))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> policyNormalizer.normalize(0.05, 0.0))
        .isInstanceOf(IllegalStateException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> policyNormalizer.normalize(0.05, -1.0))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Tổng hợp Phase 0: Chạy kịch bản A (TikTok đủ bài) và D (Instagram lệch platform)")
  void testPhase0Orchestrator_fullPipeline() {
    Campaign campaign = Campaign.builder().platforms(List.of("TIKTOK")).build();

    UUID creatorA = UUID.randomUUID();
    UUID creatorD = UUID.randomUUID();
    UUID creatorF = UUID.randomUUID();

    RecommendationCandidateProjection candA = mockCandidate(creatorA, "TIKTOK", 50_000L);
    RecommendationCandidateProjection candD = mockCandidate(creatorD, "INSTAGRAM", 60_000L);
    RecommendationCandidateProjection candF = mockCandidate(creatorF, "TIKTOK", 12_000L);

    Post pA1 = Post.builder().views(20_000L).likes(1_000L).build();
    Post pA2 = Post.builder().views(30_000L).likes(1_500L).build();
    Post pF1 = Post.builder().views(5_000L).likes(200L).build(); // F chỉ có 1 bài

    Map<UUID, List<Post>> postsMap = Map.of(
        creatorA, List.of(pA1, pA2),
        creatorF, List.of(pF1));

    List<NormalizedCandidateContext> results = orchestrator.execute(
        List.of(candA, candD, candF),
        campaign,
        Collections.emptySet(),
        postsMap);

    assertThat(results).hasSize(3);

    // Creator A: Đủ điều kiện và đủ bài -> ELIGIBLE
    var resA = results.stream().filter(r -> r.creatorId().equals(creatorA)).findFirst().orElseThrow();
    assertThat(resA.status()).isEqualTo(Phase0Status.ELIGIBLE);
    assertThat(resA.isStabImputed()).isFalse();
    assertThat(resA.erNormalized()).isBetween(0.0, 1.0);

    // Creator D: Lệch nền tảng -> INELIGIBLE
    var resD = results.stream().filter(r -> r.creatorId().equals(creatorD)).findFirst().orElseThrow();
    assertThat(resD.status()).isEqualTo(Phase0Status.INELIGIBLE);
    assertThat(resD.exclusionReasons()).contains("PLATFORM_MISMATCH: INSTAGRAM");

    // Creator F: Đúng nền tảng nhưng thiếu bài -> PENDING_DATA
    var resF = results.stream().filter(r -> r.creatorId().equals(creatorF)).findFirst().orElseThrow();
    assertThat(resF.status()).isEqualTo(Phase0Status.PENDING_DATA);
    assertThat(resF.isStabImputed()).isTrue();
    assertThat(resF.evidenceReliabilityM2()).isEqualTo(0.20);
  }

  @Test
  @DisplayName("Luồng 1: Quy tắc ưu tiên Tri-state: INELIGIBLE > PENDING_DATA > ELIGIBLE")
  void testEligibility_triStatePrecedence_ineligibleBeatsPendingData() {
    Campaign campaign = Campaign.builder().platforms(List.of("TIKTOK")).build();
    UUID creatorId = UUID.randomUUID();

    // Trường hợp 1: Platform = NULL (thiếu) VÀ creator nằm trong danh sách loại trừ
    // (feedback)
    // -> Cả 2 điều kiện xảy ra: Precedence quy định phải ra INELIGIBLE
    RecommendationCandidateProjection candBoth = mockCandidate(creatorId, null, 50_000L);
    var resBoth = eligibilityChecker.check(candBoth, campaign, Set.of(creatorId));
    assertThat(resBoth.status()).isEqualTo(Phase0Status.INELIGIBLE);
    assertThat(resBoth.isExcluded()).isTrue();
    assertThat(resBoth.reasons()).contains("EXCLUDED_BY_FEEDBACK", "MISSING_PLATFORM: Creator platform is unknown");

    // Trường hợp 2: Platform = NULL nhưng KHÔNG bị loại trừ
    // -> Chỉ thiếu dữ liệu: ra PENDING_DATA
    RecommendationCandidateProjection candMissingOnly = mockCandidate(UUID.randomUUID(), null, 50_000L);
    var resMissing = eligibilityChecker.check(candMissingOnly, campaign, Collections.emptySet());
    assertThat(resMissing.status()).isEqualTo(Phase0Status.PENDING_DATA);
    assertThat(resMissing.isPendingData()).isTrue();
    assertThat(resMissing.isExcluded()).isFalse();

    // Trường hợp 3: Platform = TIKTOK và KHÔNG bị loại trừ
    // -> Hợp lệ: ra ELIGIBLE
    RecommendationCandidateProjection candValid = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);
    var resValid = eligibilityChecker.check(candValid, campaign, Collections.emptySet());
    assertThat(resValid.status()).isEqualTo(Phase0Status.ELIGIBLE);
    assertThat(resValid.eligible()).isTrue();
  }

  @Test
  @DisplayName("Luồng 3: Error Isolation & Decoupling - Creator có dữ liệu âm KHÔNG bị xem là vi phạm Campaign")
  void testPhase0Orchestrator_errorIsolation_negativeMetrics() {
    Campaign campaign = Campaign.builder().platforms(List.of("TIKTOK")).build();
    UUID validCreatorId = UUID.randomUUID();
    UUID invalidCreatorId = UUID.randomUUID();

    RecommendationCandidateProjection validCand = mockCandidate(validCreatorId, "TIKTOK", 50_000L);

    // KOC có dữ liệu dị thường (likes âm dẫn tới ER âm)
    RecommendationCandidateProjection invalidCand = mock(RecommendationCandidateProjection.class);
    when(invalidCand.getCreatorId()).thenReturn(invalidCreatorId);
    when(invalidCand.getPlatform()).thenReturn("TIKTOK");
    when(invalidCand.getFollowers()).thenReturn(50_000L);
    when(invalidCand.getAvgViews()).thenReturn(BigDecimal.valueOf(10_000));
    when(invalidCand.getAvgLikes()).thenReturn(BigDecimal.valueOf(-500)); // Âm!
    when(invalidCand.getEngagementRate()).thenReturn(BigDecimal.valueOf(0.05));
    when(invalidCand.getAccountStatus()).thenReturn("ACTIVE");

    List<NormalizedCandidateContext> results = orchestrator.execute(
        List.of(validCand, invalidCand),
        campaign,
        Collections.emptySet(),
        Collections.emptyMap());

    assertThat(results).hasSize(2);

    var validRes = results.stream().filter(r -> r.creatorId().equals(validCreatorId)).findFirst().orElseThrow();
    assertThat(validRes.isCampaignEligible()).isTrue();
    assertThat(validRes.isDataError()).isFalse();

    var invalidRes = results.stream().filter(r -> r.creatorId().equals(invalidCreatorId)).findFirst().orElseThrow();
    // Tách biệt: Thỏa mãn Campaign nhưng bị lỗi dữ liệu!
    assertThat(invalidRes.isCampaignEligible()).isTrue();
    assertThat(invalidRes.eligibilityReasons()).isEmpty();
    assertThat(invalidRes.isDataError()).isTrue();
    assertThat(invalidRes.dataQualityReasons().get(0)).contains("DATA_QUALITY_ERROR: NEGATIVE_METRIC");
  }

  @Test
  @DisplayName("Phase 1 - SM2: Chốt Missing-Feature Policy - Mẫu số luôn luôn là 3.0 với sàn bảo thủ STAB=0.0")
  void testPublicPerformanceScorer_missingFeaturePolicy_consistentDefinition() {
    com.influencermatch.backend.recommendation.engine.memory.PublicPerformanceScorer scorer = new com.influencermatch.backend.recommendation.engine.memory.PublicPerformanceScorer();

    NormalizedCandidateContext p0MissingStab = new NormalizedCandidateContext(
        UUID.randomUUID(),
        null,
        "TIKTOK",
        Phase0Status.PENDING_DATA,
        Collections.emptyList(),
        0.60, // erNorm
        0.80, // vrNorm
        Double.NaN, // stabNorm missing
        0.06,
        0.55,
        Double.NaN,
        ErMethod.BY_VIEWS,
        1,
        true,
        0.20,
        "MVP_CALIBRATION_2026Q1");

    var res = scorer.score(p0MissingStab, null);
    // (0.60 + 0.80 + 0.0) / 3.0 = 1.40 / 3.0 ≈ 0.4667 (không chia 2, không gán
    // 0.50)
    assertThat(res.sm2Norm()).isCloseTo(1.40 / 3.0, org.assertj.core.data.Offset.offset(1e-4));
    assertThat(res.score100()).isCloseTo((1.40 / 3.0) * 100.0, org.assertj.core.data.Offset.offset(1e-2));
    assertThat(res.isStabImputed()).isTrue();
    assertThat(res.stabNorm()).isNaN();
  }

  @Test
  @DisplayName("Luồng 2: Hợp đồng ER - Tỷ lệ 1.20 (120% tương tác) là hợp lệ, không tự báo lỗi scale")
  void testEvidenceValidator_erAboveOneIsValidDecimalRatio() {
    RecommendationCandidateProjection cand = mock(RecommendationCandidateProjection.class);
    when(cand.getCreatorId()).thenReturn(UUID.randomUUID());
    when(cand.getPlatform()).thenReturn("TIKTOK");
    when(cand.getFollowers()).thenReturn(10_000L);
    when(cand.getAvgViews()).thenReturn(BigDecimal.valueOf(5_000));
    when(cand.getAvgLikes()).thenReturn(BigDecimal.valueOf(6_000)); // 6000 / 5000 = 1.20
    when(cand.getEngagementRate()).thenReturn(BigDecimal.valueOf(1.20)); // 120%

    var metrics = evidenceValidator.evaluate(cand, Collections.emptyList());
    assertThat(metrics.rawEr()).isEqualTo(1.20);
    // Khi chuẩn hóa qua hàm bão hòa: S = 1.20 / (1.20 + 0.05) = 1.20 / 1.25 = 0.96
    double s = policyNormalizer.normalize(metrics.rawEr(), 0.05);
    assertThat(s).isEqualTo(0.96);
  }

  @Test
  @DisplayName("Luồng 2: Outlier Detection - Từ chối giá trị ER dị thường cực đoan > 10.0 (1000% tương tác)")
  void testEvidenceValidator_rejectsExtremeOutlierErAboveTen() {
    RecommendationCandidateProjection cand = mock(RecommendationCandidateProjection.class);
    when(cand.getCreatorId()).thenReturn(UUID.randomUUID());
    when(cand.getPlatform()).thenReturn("TIKTOK");
    when(cand.getFollowers()).thenReturn(10_000L);
    when(cand.getEngagementRate()).thenReturn(BigDecimal.valueOf(15.0)); // > 10.0

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> evidenceValidator.evaluate(cand, Collections.emptyList()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("EXTREME_OUTLIER_ER");
  }

  @Test
  @DisplayName("Luồng 2: Video có 3 bài nhưng đều 0 views - Mẫu số views = 0 không thể tính ER, xếp loại NOT_SCORABLE")
  void testEvidenceValidator_zeroViewsWithPostsProducesProvisionalNotReady() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);

    // 3 bài nhưng toàn bộ views = 0
    Post p1 = Post.builder().views(0L).likes(10L).build();
    Post p2 = Post.builder().views(0L).likes(15L).build();
    Post p3 = Post.builder().views(0L).likes(20L).build();

    var metrics = evidenceValidator.evaluate(cand, List.of(p1, p2, p3));
    assertThat(metrics.canComputeStability()).isFalse();
    assertThat(metrics.scoringReadiness())
        .isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.NOT_SCORABLE);
    assertThat(metrics.dataQualityReasons()).anyMatch(r -> r.contains("ER_UNAVAILABLE_ZERO_VIEWS_DENOMINATOR"));
  }

  @Test
  @DisplayName("Luồng 1: Ràng buộc Account Status - SUSPENDED/DELETED bị loại INELIGIBLE, NULL/UNKNOWN ra PENDING")
  void testEligibility_accountStatusValidation() {
    Campaign campaign = Campaign.builder().platforms(List.of("TIKTOK")).build();

    // 1. ACTIVE -> ELIGIBLE
    RecommendationCandidateProjection activeCand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);
    when(activeCand.getAccountStatus()).thenReturn("ACTIVE");
    var resActive = eligibilityChecker.check(activeCand, campaign, Collections.emptySet());
    assertThat(resActive.eligible()).isTrue();
    assertThat(resActive.reasons()).isEmpty();

    // 2. SUSPENDED -> INELIGIBLE
    RecommendationCandidateProjection suspendedCand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);
    when(suspendedCand.getAccountStatus()).thenReturn("SUSPENDED");
    EligibilityChecker.Result resSuspended = eligibilityChecker.check(suspendedCand, campaign, Collections.emptySet());
    assertThat(resSuspended.eligible()).isFalse();
    assertThat(resSuspended.isExcluded()).isTrue();
    assertThat(resSuspended.status()).isEqualTo(Phase0Status.INELIGIBLE);
    assertThat(resSuspended.campaignEligibility()).isEqualTo(CampaignEligibility.INELIGIBLE);
    assertThat(resSuspended.reasons()).contains("ACCOUNT_INACTIVE: Account status is SUSPENDED");

    // 3. NULL / UNKNOWN -> PENDING_VERIFICATION
    RecommendationCandidateProjection nullStatusCand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);
    when(nullStatusCand.getAccountStatus()).thenReturn(null);
    EligibilityChecker.Result resNull = eligibilityChecker.check(nullStatusCand, campaign, Collections.emptySet());
    assertThat(resNull.isPendingData()).isTrue();
    assertThat(resNull.status()).isEqualTo(Phase0Status.PENDING_DATA);
    assertThat(resNull.campaignEligibility()).isEqualTo(CampaignEligibility.PENDING_VERIFICATION);
    assertThat(resNull.reasons()).contains("UNKNOWN_ACCOUNT_STATUS: Creator account status is unknown");

    // 4. NULL status VÀ bị Brand Feedback loại trừ -> INELIGIBLE (Precedence INELIGIBLE > PENDING)
    UUID rejectedId = UUID.randomUUID();
    RecommendationCandidateProjection rejectedNullCand = mockCandidate(rejectedId, "TIKTOK", 50_000L);
    when(rejectedNullCand.getAccountStatus()).thenReturn(null);
    EligibilityChecker.Result resRejectedNull = eligibilityChecker.check(rejectedNullCand, campaign, Set.of(rejectedId));
    assertThat(resRejectedNull.isExcluded()).isTrue();
    assertThat(resRejectedNull.status()).isEqualTo(Phase0Status.INELIGIBLE);
    assertThat(resRejectedNull.campaignEligibility()).isEqualTo(CampaignEligibility.INELIGIBLE);
    assertThat(resRejectedNull.reasons()).contains("EXCLUDED_BY_FEEDBACK",
        "UNKNOWN_ACCOUNT_STATUS: Creator account status is unknown");
  }

  @Test
  @DisplayName("Luồng 2: Cửa sổ 3 bài đo độ ổn định - DB có 20 bài, hệ thống chọn đúng 3 bài hợp lệ gần nhất")
  void testEvidenceValidator_twentyPostsInDb_strictlySelectsLatestThreeValidPosts() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(creatorId, "TIKTOK", 50_000L);

    List<com.influencermatch.backend.creator.model.Post> posts = new ArrayList<>();
    java.time.LocalDateTime now = java.time.LocalDateTime.now();

    // 17 bài cũ với views = 1,000 (đăng từ 20 ngày trước đến 4 ngày trước)
    for (int i = 20; i >= 4; i--) {
      posts.add(com.influencermatch.backend.creator.model.Post.builder()
          .views(1_000L).likes(50L).comments(10L).shares(5L)
          .createdAt(now.minusDays(i))
          .build());
    }

    // 3 bài gần nhất: views = 10,000, 20,000, 30,000 (mean = 20,000; s_V = 10,000; CV = 0.50; STAB = 1 / 1.50 = 0.6667)
    posts.add(com.influencermatch.backend.creator.model.Post.builder()
        .views(10_000L).likes(500L).comments(100L).shares(50L)
        .createdAt(now.minusDays(3))
        .build());
    posts.add(com.influencermatch.backend.creator.model.Post.builder()
        .views(20_000L).likes(1_000L).comments(200L).shares(100L)
        .createdAt(now.minusDays(2))
        .build());
    posts.add(com.influencermatch.backend.creator.model.Post.builder()
        .views(30_000L).likes(1_500L).comments(300L).shares(150L)
        .createdAt(now.minusDays(1))
        .build());

    var metrics = evidenceValidator.evaluate(cand, posts);

    assertThat(metrics.canComputeStability()).isTrue();
    assertThat(metrics.isStabImputed()).isFalse();
    // CV = 10,000 / 20,000 = 0.5; STAB = 1 / (1 + 0.5) = 2/3 ≈ 0.6667
    assertThat(metrics.rawStab()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-4));
    // Độ tin cậy r_i2 vẫn phản ánh lịch sử có 20 bài (>= 10 bài -> 1.00)
    assertThat(metrics.evidenceReliabilityM2()).isEqualTo(1.00);
  }

  @Test
  @DisplayName("Luồng 2: Ưu tiên ngày đăng thực tế (postedAt) hơn ngày crawler lưu (createdAt)")
  void testEvidenceValidator_postedAtPrioritizedOverCreatedAt_whenCrawlDatesDiffer() {
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(creatorId, "TIKTOK", 50_000L);

    List<com.influencermatch.backend.creator.model.Post> posts = new ArrayList<>();
    // Giả sử crawler lưu tất cả các bài vào cùng 1 ngày (hôm nay)
    java.time.LocalDateTime crawlDate = java.time.LocalDateTime.now();

    // Bài cũ P1: đăng ngày 01/11, views = 1,000
    posts.add(com.influencermatch.backend.creator.model.Post.builder()
        .views(1_000L).likes(50L).comments(10L).shares(5L)
        .postedAt("2026-11-01T10:00:00Z")
        .createdAt(crawlDate)
        .build());

    // 3 bài đăng mới thật sự:
    // P2: đăng ngày 05/11, views = 10,000
    posts.add(com.influencermatch.backend.creator.model.Post.builder()
        .views(10_000L).likes(500L).comments(100L).shares(50L)
        .postedAt("2026-11-05T10:00:00Z")
        .createdAt(crawlDate)
        .build());
    // P3: đăng ngày 06/11, views = 20,000
    posts.add(com.influencermatch.backend.creator.model.Post.builder()
        .views(20_000L).likes(1_000L).comments(200L).shares(100L)
        .postedAt("2026-11-06T10:00:00Z")
        .createdAt(crawlDate)
        .build());
    // P4: đăng ngày 07/11, views = 30,000
    posts.add(com.influencermatch.backend.creator.model.Post.builder()
        .views(30_000L).likes(1_500L).comments(300L).shares(150L)
        .postedAt("2026-11-07T10:00:00Z")
        .createdAt(crawlDate)
        .build());

    var metrics = evidenceValidator.evaluate(cand, posts);

    // Hệ thống chọn đúng 3 bài đăng ngày 05, 06, 07/11 (bỏ bài ngày 01/11 dù cùng createdAt)
    assertThat(metrics.canComputeStability()).isTrue();
    assertThat(metrics.rawStab()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-4));
  }

  @Test
  @DisplayName("Luồng 1: Soft Preference - Creator có 120k Followers trong Campaign [10k, 100k] vẫn ELIGIBLE, chỉ gắn cờ outsideFollowers")
  void testEligibilityChecker_softConstraintFollowers_outsideRangeIsStillEligible() {
    Campaign campaign = mock(Campaign.class);
    when(campaign.getPlatforms()).thenReturn(List.of("TIKTOK"));
    when(campaign.getFollowerMin()).thenReturn(10_000L);
    when(campaign.getFollowerMax()).thenReturn(100_000L);

    RecommendationCandidateProjection cand120k = mockCandidate(UUID.randomUUID(), "TIKTOK", 120_000L);

    var res = eligibilityChecker.check(cand120k, campaign, Collections.emptySet());

    assertThat(res.eligible()).isTrue();
    assertThat(res.isExcluded()).isFalse();
    assertThat(res.outsideFollowers()).isTrue();
  }

  @Test
  @DisplayName("Phase 4: TOPSIS từ chối input NaN - Không bao giờ biến lỗi tính toán thành điểm 0.0 hợp lệ")
  void testTopsisEngine_rejectsNanInput_doesNotMaskAsZeroScore() {
    var topsisEngine = new com.influencermatch.backend.recommendation.engine.topsis.TopsisRankingEngine();
    var weights = new com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights(
        0.25, 0.25, 0.25, 0.25, "TEST", 0.0);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> topsisEngine.compute(0.5, Double.NaN, 0.8, 0.6, weights))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TOPSIS_INVALID_INPUT");
  }

  @Test
  @DisplayName("Luồng 2: Giá trị Views = 0 là quan sát thực tế hợp lệ - Không bị loại bỏ, tính đúng CV ~ 0.866 và STAB ~ 0.536")
  void testEvidenceValidator_zeroViewsIncludedInStabilitySample_calculatesAccurateCv() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);
    List<Post> posts = List.of(
        Post.builder().id(UUID.randomUUID()).views(0L).likes(0L).comments(0L).shares(0L).postedAt("2026-11-03T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(100L).likes(10L).comments(2L).shares(1L).postedAt("2026-11-02T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(100L).likes(10L).comments(2L).shares(1L).postedAt("2026-11-01T10:00:00Z").build()
    );

    var metrics = evidenceValidator.evaluate(cand, posts);

    // Mẫu: [0, 100, 100], N = 3, Mean = 66.667, sV = 57.735, CV = 0.866, STAB = 1 / (1 + 0.866) = 0.535898
    // Nếu loại bỏ 0, mẫu thành [100, 100] thì STAB sẽ sai lệch thành 1.0
    assertThat(metrics.canComputeStability()).isTrue();
    assertThat(metrics.rawStab()).isCloseTo(0.535898, org.assertj.core.data.Offset.offset(1e-4));
    assertThat(metrics.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.SCORE_READY);
  }

  @Test
  @DisplayName("Luồng 2: Cả 3 bài đều có Views = 0 thì V_mean = 0 và ER = NaN - Xếp vào NOT_SCORABLE")
  void testEvidenceValidator_allZeroViewsInWindow_returnsProvisionalAndNanStability() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);
    List<Post> posts = List.of(
        Post.builder().id(UUID.randomUUID()).views(0L).likes(0L).comments(0L).shares(0L).postedAt("2026-11-03T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(0L).likes(0L).comments(0L).shares(0L).postedAt("2026-11-02T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(0L).likes(0L).comments(0L).shares(0L).postedAt("2026-11-01T10:00:00Z").build()
    );

    var metrics = evidenceValidator.evaluate(cand, posts);

    assertThat(metrics.canComputeStability()).isFalse();
    assertThat(Double.isNaN(metrics.rawStab())).isTrue();
    assertThat(metrics.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.NOT_SCORABLE);
    assertThat(metrics.dataQualityReasons()).anyMatch(r -> r.contains("ER_UNAVAILABLE_ZERO_VIEWS_DENOMINATOR"));
  }

  @Test
  @DisplayName("Phase 4: TOPSIS từ chối điểm ngoài miền [0, 1] - ném ngoại lệ khi score > 1.0 hoặc < 0.0")
  void testTopsisEngine_rejectsOutOfBoundDomainScores_greaterThanOneOrNegative() {
    var topsisEngine = new com.influencermatch.backend.recommendation.engine.topsis.TopsisRankingEngine();
    var weights = new com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights(
        0.25, 0.25, 0.25, 0.25, "TEST", 0.0);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> topsisEngine.compute(1.5, 0.5, 0.8, 0.6, weights))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TOPSIS_INVALID_INPUT");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> topsisEngine.compute(0.5, -0.2, 0.8, 0.6, weights))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TOPSIS_INVALID_INPUT");
  }

  @Test
  @DisplayName("Phase 4: TOPSIS từ chối trọng số không hợp lệ - ném ngoại lệ khi tổng trọng số != 1.0 hoặc trọng số âm")
  void testTopsisEngine_rejectsInvalidWeights_notSumToOneOrNegative() {
    var topsisEngine = new com.influencermatch.backend.recommendation.engine.topsis.TopsisRankingEngine();

    // Tổng trọng số 0.3 * 4 = 1.2 (!= 1.0)
    var weightsOver1 = new com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights(
        0.30, 0.30, 0.30, 0.30, "TEST", 0.0);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> topsisEngine.compute(0.5, 0.5, 0.8, 0.6, weightsOver1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Criteria weights sum must equal 1.0");

    // Trọng số âm
    var negativeWeight = new com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights(
        -0.10, 0.40, 0.40, 0.30, "TEST", 0.0);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> topsisEngine.compute(0.5, 0.5, 0.8, 0.6, negativeWeight))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Criteria weights must be non-negative");
  }

  @Test
  @DisplayName("Luồng 2: Video có tổng Views = 0 và Interactions = 0 - ER không xác định (0/0 là NaN), xếp vào NOT_SCORABLE thay vì tự gán 0.0")
  void testEvidenceValidator_zeroViewsVideos_returnsIndeterminateErWhenTotalViewsZero() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);
    List<Post> posts = List.of(
        Post.builder().id(UUID.randomUUID()).views(0L).likes(0L).comments(0L).shares(0L).postedAt("2026-11-03T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(0L).likes(0L).comments(0L).shares(0L).postedAt("2026-11-02T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(0L).likes(0L).comments(0L).shares(0L).postedAt("2026-11-01T10:00:00Z").build()
    );

    var metrics = evidenceValidator.evaluate(cand, posts);

    assertThat(metrics.erMethod()).isEqualTo(ErMethod.BY_VIEWS);
    // 0 / 0 là bất định toán học (NaN), không được giả định bằng 0.0
    assertThat(Double.isNaN(metrics.rawEr())).isTrue();
    assertThat(metrics.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.NOT_SCORABLE);
    assertThat(metrics.dataQualityReasons()).anyMatch(r -> r.contains("ER_UNAVAILABLE_ZERO_VIEWS_DENOMINATOR"));
  }

  @Test
  @DisplayName("Luồng 2: Video có Views > 0 nhưng Interactions = 0 - ER = 0.0 hợp lệ thực tế")
  void testEvidenceValidator_videoWithViewsAndZeroInteractions_returnsZeroEr() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);
    List<Post> posts = List.of(
        Post.builder().id(UUID.randomUUID()).views(100L).likes(0L).comments(0L).shares(0L).postedAt("2026-11-03T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(100L).likes(0L).comments(0L).shares(0L).postedAt("2026-11-02T10:00:00Z").build()
    );

    var metrics = evidenceValidator.evaluate(cand, posts);

    assertThat(metrics.erMethod()).isEqualTo(ErMethod.BY_VIEWS);
    // 0 tương tác trên 200 lượt xem = 0.0 (hợp lệ)
    assertThat(metrics.rawEr()).isEqualTo(0.0);
    assertThat(metrics.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.SCORE_READY);
  }

  @Test
  @DisplayName("Luồng 2: Bài ảnh không có trường Views - ER tính theo Followers, VR thiếu do đặc thù loại nội dung -> Xếp loại NOT_SCORABLE (không gọi là dữ liệu hỏng)")
  void testEvidenceValidator_photoPostsWithoutViews_usesByFollowersMethodAndClassifiedNotScorable() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "INSTAGRAM", 10_000L);
    when(cand.getAvgViews()).thenReturn(null);
    List<Post> posts = List.of(
        Post.builder().id(UUID.randomUUID()).views(null).likes(100L).comments(20L).shares(5L).postedAt("2026-11-02T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(null).likes(100L).comments(20L).shares(5L).postedAt("2026-11-01T10:00:00Z").build()
    );

    var metrics = evidenceValidator.evaluate(cand, posts);

    assertThat(metrics.erMethod()).isEqualTo(ErMethod.BY_FOLLOWERS);
    // (125 + 125) / 2 = 125 interactions/post; ER = 125 / 10,000 = 0.0125
    assertThat(metrics.rawEr()).isCloseTo(0.0125, org.assertj.core.data.Offset.offset(1e-5));
    // Bài ảnh không có Views -> VR = NaN -> Xếp vào NOT_SCORABLE (không phải INVALID_DATA)
    assertThat(metrics.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.NOT_SCORABLE);
    assertThat(metrics.dataQualityReasons()).anyMatch(r -> r.contains("UNSUPPORTED_METRIC_VIEWS"));
  }

  @Test
  @DisplayName("Luồng 2: Bài hỗn hợp (Video có Views + Ảnh không Views) - Không cộng tương tác của bài ảnh vào tử số của BY_VIEWS")
  void testEvidenceValidator_mixedPosts_doesNotInflateErByAddingPhotoLikesToVideoViews() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "INSTAGRAM", 10_000L);
    List<Post> posts = List.of(
        Post.builder().id(UUID.randomUUID()).views(1_000L).likes(50L).comments(10L).shares(5L).postedAt("2026-11-02T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(null).likes(500L).comments(50L).shares(10L).postedAt("2026-11-01T10:00:00Z").build()
    );

    var metrics = evidenceValidator.evaluate(cand, posts);

    assertThat(metrics.erMethod()).isEqualTo(ErMethod.BY_VIEWS);
    // ER = 65 interactions trên video / 1,000 views = 0.065 (Không được cộng 560 tương tác của bài ảnh thành 625 / 1000 = 0.625)
    assertThat(metrics.rawEr()).isCloseTo(0.065, org.assertj.core.data.Offset.offset(1e-4));
  }

  @Test
  @DisplayName("Luồng 2: DB ER = 0.0 thực tế quan sát được - Giữ nguyên 0.0, không coi là missing để fallback sang likes/views")
  void testEvidenceValidator_dbErZeroObserved_preservesZeroWithoutFallback() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);
    when(cand.getEngagementRate()).thenReturn(BigDecimal.valueOf(0.0));
    when(cand.getAvgLikes()).thenReturn(BigDecimal.valueOf(500));
    when(cand.getAvgViews()).thenReturn(BigDecimal.valueOf(10_000));

    var metrics = evidenceValidator.evaluate(cand, Collections.emptyList());

    assertThat(metrics.rawEr()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("Luồng 2: Thiếu dữ liệu tính ER và VR - Xếp loại NOT_SCORABLE, không cho phép vào PROVISIONAL")
  void testEvidenceValidator_missingErAndNoWayToCompute_classifiedAsNotScorable() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);
    when(cand.getEngagementRate()).thenReturn(null);
    when(cand.getAvgLikes()).thenReturn(null);
    when(cand.getAvgViews()).thenReturn(null);

    var metrics = evidenceValidator.evaluate(cand, Collections.emptyList());

    assertThat(Double.isNaN(metrics.rawEr())).isTrue();
    assertThat(metrics.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.NOT_SCORABLE);
    assertThat(metrics.dataQualityReasons()).anyMatch(r -> r.contains("UNSUPPORTED_OR_MISSING_ER") || r.contains("NO_POST_EVIDENCE"));
  }

  @Test
  @DisplayName("Luồng 2: Timezone ISO-8601 không offset được chuẩn hóa sang Asia/Ho_Chi_Minh khi nguồn xác nhận")
  void testEvidenceValidator_resolvePostTime_handlesLocalDateTimeWithoutTimezoneAsTargetZone() {
    Post post = Post.builder().postedAt("2026-11-05T12:00:00").build();
    java.time.Instant resolved = EvidenceValidator.resolvePostTime(post, EvidenceValidator.VN_SOURCE_ZONE);

    // 12:00 tại GMT+7 tương ứng với 05:00:00Z UTC
    assertThat(resolved).isEqualTo(java.time.Instant.parse("2026-11-05T05:00:00Z"));
  }

  @Test
  @DisplayName("Reviewer Check 1: Video Views = 0, Interactions = 0 - Orchestrator giữ ELIGIBLE và NOT_SCORABLE, không biến thành INVALID_DATA")
  void testReviewerCheck1_zeroViewsVideos_orchestratorPreservesNotScorableNotInvalidData() {
    Campaign campaign = Campaign.builder().platforms(List.of("TIKTOK")).build();
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(creatorId, "TIKTOK", 50_000L);
    List<Post> posts = List.of(
        Post.builder().id(UUID.randomUUID()).views(0L).likes(0L).comments(0L).shares(0L).postedAt("2026-11-03T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(0L).likes(0L).comments(0L).shares(0L).postedAt("2026-11-02T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(0L).likes(0L).comments(0L).shares(0L).postedAt("2026-11-01T10:00:00Z").build()
    );

    List<NormalizedCandidateContext> results = orchestrator.execute(
        List.of(cand), campaign, Collections.emptySet(), Map.of(creatorId, posts)
    );

    assertThat(results).hasSize(1);
    var res = results.get(0);
    assertThat(res.isCampaignEligible()).isTrue();
    assertThat(res.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.NOT_SCORABLE);
    assertThat(res.isNotScorable()).isTrue();
    assertThat(res.isDataError()).isFalse();
    assertThat(res.dataQualityReasons()).anyMatch(r -> r.contains("ER_UNAVAILABLE_ZERO_VIEWS_DENOMINATOR"));
  }

  @Test
  @DisplayName("Reviewer Check 2: Video có Views null do crawler lỗi - Xếp NOT_SCORABLE với lý do Missing, không phải Unsupported")
  void testReviewerCheck2_videoPlatformNullViewsDueToCrawler_classifiedMissingViewMetrics() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "TIKTOK", 50_000L);
    when(cand.getAvgViews()).thenReturn(null);
    List<Post> posts = List.of(
        Post.builder().id(UUID.randomUUID()).views(null).likes(100L).comments(20L).shares(5L).postedAt("2026-11-02T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(null).likes(120L).comments(15L).shares(8L).postedAt("2026-11-01T10:00:00Z").build()
    );

    var metrics = evidenceValidator.evaluate(cand, posts);
    assertThat(metrics.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.NOT_SCORABLE);
    assertThat(metrics.dataQualityReasons()).anyMatch(r -> r.contains("MISSING_VIEW_METRICS"));
    assertThat(metrics.dataQualityReasons()).noneMatch(r -> r.contains("UNSUPPORTED_METRIC_VIEWS"));
  }

  @Test
  @DisplayName("Reviewer Check 3: postedAt không offset nhưng nguồn xác nhận UTC - Không bị chuyển lệch bảy giờ")
  void testReviewerCheck3_unoffsettedTimestampWithUtcSourceConvention_noSevenHourShift() {
    Post post = Post.builder().postedAt("2026-11-08T10:00:00").build();

    // 1. Mặc định / nguồn xác nhận UTC: đúng 10:00:00Z UTC, không bị lệch 7 giờ sang 03:00:00Z
    java.time.Instant utcInstant = EvidenceValidator.resolvePostTime(post, java.time.ZoneOffset.UTC);
    assertThat(utcInstant).isEqualTo(java.time.Instant.parse("2026-11-08T10:00:00Z"));

    // Default overload không truyền zone cũng tuân thủ chuẩn UTC:
    java.time.Instant defaultInstant = EvidenceValidator.resolvePostTime(post);
    assertThat(defaultInstant).isEqualTo(java.time.Instant.parse("2026-11-08T10:00:00Z"));

    // 2. Khi hợp đồng nguồn xác nhận là giờ Việt Nam (Asia/Ho_Chi_Minh): 10:00 GMT+7 tương đương 03:00 UTC
    java.time.Instant vnInstant = EvidenceValidator.resolvePostTime(post, EvidenceValidator.VN_SOURCE_ZONE);
    assertThat(vnInstant).isEqualTo(java.time.Instant.parse("2026-11-08T03:00:00Z"));

    // 3. Chuỗi có offset rõ ràng (+07:00 hoặc Z): luôn parse theo đúng offset
    Post postWithOffset = Post.builder().postedAt("2026-11-08T10:00:00+07:00").build();
    assertThat(EvidenceValidator.resolvePostTime(postWithOffset)).isEqualTo(java.time.Instant.parse("2026-11-08T03:00:00Z"));
  }

  @Test
  @DisplayName("Reviewer Check 4: Bài ảnh không Views nhưng DB có Average Views hợp lệ - Quyết định dựa trên Metric Provenance, xếp vào PROVISIONAL")
  void testReviewerCheck4_photoPostsWithDbAverageViewsProvenance_classifiedProvisional() {
    RecommendationCandidateProjection cand = mockCandidate(UUID.randomUUID(), "INSTAGRAM", 10_000L);
    // Profile DB có averageViews = 4_000 từ nguồn channel-level analytics đã xác minh
    when(cand.getAvgViews()).thenReturn(BigDecimal.valueOf(4_000));

    // Bài đăng là bài ảnh (views = null)
    List<Post> photoPosts = List.of(
        Post.builder().id(UUID.randomUUID()).views(null).likes(200L).comments(30L).shares(10L).postedAt("2026-11-02T10:00:00Z").build(),
        Post.builder().id(UUID.randomUUID()).views(null).likes(180L).comments(20L).shares(10L).postedAt("2026-11-01T10:00:00Z").build()
    );

    var metrics = evidenceValidator.evaluate(cand, photoPosts);

    // ER tính theo followers: (240 + 210) / 2 = 225; 225 / 10,000 = 0.0225 (hợp lệ)
    assertThat(metrics.rawEr()).isCloseTo(0.0225, org.assertj.core.data.Offset.offset(1e-4));
    // VR lấy từ DB average views qua Metric Provenance: 4,000 / 10,000 = 0.40 (hợp lệ)
    assertThat(metrics.rawVr()).isCloseTo(0.40, org.assertj.core.data.Offset.offset(1e-4));
    // STAB không thể tính từ bài ảnh đơn lẻ (cửa sổ không có video views) -> NaN
    assertThat(metrics.canComputeStability()).isFalse();
    assertThat(Double.isNaN(metrics.rawStab())).isTrue();

    // CẢ ER và VR đều hợp lệ, chỉ thiếu STAB -> Đủ điều kiện xếp vào PROVISIONAL (không bị loại oan)
    assertThat(metrics.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.PROVISIONAL);
    assertThat(metrics.dataQualityReasons()).anyMatch(r -> r.contains("SHORT_WINDOW_INSUFFICIENT_POSTS"));
    assertThat(metrics.dataQualityReasons()).anyMatch(r -> r.contains("UNVERIFIED_AGGREGATE_VIEW_PROVENANCE"));
    assertThat(metrics.evidenceReliabilityM2()).isEqualTo(0.20);
  }

  @Test
  @DisplayName("Reviewer Check 5: NOT_SCORABLE có erMethod = null và rawEr = NaN - Orchestrator không gọi resolveKEr hay normalize")
  void testPhase0Orchestrator_notScorableWithNullErMethod_bypassesResolveKErAndNormalizer() {
    Campaign campaign = Campaign.builder().platforms(List.of("TIKTOK")).build();
    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(creatorId, "TIKTOK", 50_000L);
    when(cand.getEngagementRate()).thenReturn(null);
    when(cand.getAvgLikes()).thenReturn(null);
    when(cand.getAvgViews()).thenReturn(null);

    // Creator không có bài nào và không có DB metric -> NOT_SCORABLE
    List<NormalizedCandidateContext> results = orchestrator.execute(
        List.of(cand), campaign, Collections.emptySet(), Collections.emptyMap()
    );

    assertThat(results).hasSize(1);
    var res = results.get(0);
    assertThat(res.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.NOT_SCORABLE);
    assertThat(Double.isNaN(res.erNormalized())).isTrue();
    assertThat(Double.isNaN(res.vrNormalized())).isTrue();
    assertThat(Double.isNaN(res.stabNormalized())).isTrue();
  }

  @Test
  @DisplayName("Reviewer Check 6: Timezone nguồn xác nhận được truyền vào selectRecentWindow quyết định chính xác thứ tự bài")
  void testEvidenceValidator_selectRecentWindow_withSourceZoneContract_ordersPostsDeterministically() {
    // Post A: "2026-11-05T12:00:00" (chuỗi không offset)
    // Post B: "2026-11-05T06:00:00Z" (chuỗi UTC rõ ràng = 06:00 UTC)
    UUID idA = UUID.randomUUID();
    UUID idB = UUID.randomUUID();
    Post postA = Post.builder().id(idA).postedAt("2026-11-05T12:00:00").build();
    Post postB = Post.builder().id(idB).postedAt("2026-11-05T06:00:00Z").build();
    List<Post> posts = List.of(postA, postB);

    // Kịch bản 1: Nguồn Post A xác nhận theo hợp đồng là giờ Việt Nam (Asia/Ho_Chi_Minh):
    // 12:00 GMT+7 tương đương 05:00 UTC. Do đó Post B (06:00 UTC) mới hơn Post A (05:00 UTC).
    List<Post> windowVn = EvidenceValidator.selectRecentWindow(posts, 1, EvidenceValidator.VN_SOURCE_ZONE);
    assertThat(windowVn.get(0).getId()).isEqualTo(idB);

    // Kịch bản 2: Nguồn Post A theo chuẩn quốc tế UTC:
    // 12:00 UTC mới hơn Post B (06:00 UTC). Do đó Post A mới hơn.
    List<Post> windowUtc = EvidenceValidator.selectRecentWindow(posts, 1, java.time.ZoneOffset.UTC);
    assertThat(windowUtc.get(0).getId()).isEqualTo(idA);
  }

  @Test
  @DisplayName("Reviewer Test 1: Campaign Location = VN, nhưng Source Timestamp = UTC -> Giữ đúng UTC, không dịch sang giờ VN")
  void testReviewerItem1_campaignLocationVn_doesNotShiftUtcSourceTimestamp() {
    Campaign campaignVn = Campaign.builder()
        .locations(List.of("VIETNAM", "VN"))
        .platforms(List.of("TIKTOK"))
        .build();

    UUID creatorId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(creatorId, "TIKTOK", 50_000L);

    // Post 1: 10:00 UTC (có Z rõ ràng)
    // Post 2: 15:00 GMT+7 (có offset +07:00 rõ ràng = 08:00 UTC)
    Post post1 = Post.builder()
        .id(UUID.randomUUID())
        .postedAt("2026-11-08T10:00:00Z")
        .views(10_000L).likes(500L).comments(50L).shares(10L)
        .build();
    Post post2 = Post.builder()
        .id(UUID.randomUUID())
        .postedAt("2026-11-08T15:00:00+07:00")
        .views(12_000L).likes(600L).comments(60L).shares(12L)
        .build();

    // Verify resolvePostTime bảo toàn chính xác thời điểm thực UTC:
    // Post 1 (10:00 UTC) mới hơn Post 2 (08:00 UTC), bất kể Campaign có nhắm mục tiêu Việt Nam hay không
    assertThat(EvidenceValidator.resolvePostTime(post1)).isEqualTo(java.time.Instant.parse("2026-11-08T10:00:00Z"));
    assertThat(EvidenceValidator.resolvePostTime(post2)).isEqualTo(java.time.Instant.parse("2026-11-08T08:00:00Z"));
    assertThat(EvidenceValidator.resolvePostTime(post1)).isAfter(EvidenceValidator.resolvePostTime(post2));

    // Orchestrator thực thi với campaignVn: thứ tự bài đăng và đánh giá ổn định độc lập hoàn toàn với Campaign Location
    List<NormalizedCandidateContext> results = orchestrator.execute(
        List.of(cand), campaignVn, Collections.emptySet(), Map.of(creatorId, List.of(post2, post1))
    );

    assertThat(results).hasSize(1);
    var res = results.get(0);
    assertThat(res.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.SCORE_READY); // 2 bài hợp lệ có views > 0 trong window -> tính được STAB -> SCORE_READY
    assertThat(res.rawStab()).isGreaterThan(0.0);
  }

  @Test
  @DisplayName("Reviewer Test 4: Có offset, không offset và timestamp lỗi trong cùng tập Posts -> Chọn cửa sổ ba bài theo đúng Data Source Contract")
  void testReviewerItem4_mixedOffsetNaiveAndMalformedTimestamps_selectsWindowCorrectly() {
    UUID cId = UUID.randomUUID();
    RecommendationCandidateProjection cand = mockCandidate(cId, "TIKTOK", 50_000L);

    // P1: 12:00 UTC
    Post p1 = Post.builder().id(UUID.randomUUID()).postedAt("2026-11-08T12:00:00Z").views(20_000L).likes(1_000L).build();
    // P2: 18:00 GMT+7 (= 11:00 UTC)
    Post p2 = Post.builder().id(UUID.randomUUID()).postedAt("2026-11-08T18:00:00+07:00").views(18_000L).likes(900L).build();
    // P3: 10:00 Naive (không offset -> Data Source Contract UTC = 10:00 UTC)
    Post p3 = Post.builder().id(UUID.randomUUID()).postedAt("2026-11-08T10:00:00").views(15_000L).likes(750L).build();
    // P4: Lỗi format postedAt, fallback createdAt = 2026-11-01T00:00:00 (cũ hơn nhiều)
    Post p4 = Post.builder().id(UUID.randomUUID()).postedAt("invalid-timestamp-string")
        .createdAt(java.time.LocalDateTime.of(2026, 11, 1, 0, 0))
        .views(5_000L).likes(250L).build();

    // Chọn cửa sổ 3 bài: Thứ tự thời gian phải là P1 (12:00 UTC) > P2 (11:00 UTC) > P3 (10:00 UTC). P4 bị loại vì cũ nhất.
    List<Post> selected = EvidenceValidator.selectRecentWindow(
        List.of(p4, p3, p1, p2), 3, DataSourceContract.resolveSourceZone("TIKTOK")
    );

    assertThat(selected).hasSize(3);
    assertThat(selected.get(0).getId()).isEqualTo(p1.getId());
    assertThat(selected.get(1).getId()).isEqualTo(p2.getId());
    assertThat(selected.get(2).getId()).isEqualTo(p3.getId());

    // EvidenceValidator đánh giá tập bài hỗn hợp này an toàn, không ném exception và ghi nhận cảnh báo chất lượng
    var metrics = evidenceValidator.evaluate(cand, List.of(p4, p3, p1, p2), DataSourceContract.resolveSourceZone("TIKTOK"));
    assertThat(metrics.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.SCORE_READY);
    assertThat(metrics.dataQualityReasons()).anyMatch(r -> r.contains("TIMEZONE_UNVERIFIED"));
  }

  private RecommendationCandidateProjection mockCandidate(UUID id, String platform, Long followers) {
    RecommendationCandidateProjection c = mock(RecommendationCandidateProjection.class);
    when(c.getCreatorId()).thenReturn(id);
    when(c.getPlatform()).thenReturn(platform);
    when(c.getFollowers()).thenReturn(followers);
    when(c.getAvgViews()).thenReturn(BigDecimal.valueOf(10_000));
    when(c.getAvgLikes()).thenReturn(BigDecimal.valueOf(500));
    when(c.getEngagementRate()).thenReturn(BigDecimal.valueOf(0.05));
    when(c.getAccountStatus()).thenReturn("ACTIVE");
    return c;
  }
}
