package com.influencermatch.backend.recommendation.engine.phase0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
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
    assertThat(metrics.evidenceReliabilityM2()).isEqualTo(0.85);

    // KOC chỉ có 1 bài -> không thể tính độ lệch chuẩn mẫu s_V
    Post singlePost = Post.builder().views(12_000L).likes(500L).build();
    var singleMetrics = evidenceValidator.evaluate(cand, List.of(singlePost));
    assertThat(singleMetrics.canComputeStability()).isFalse();
    assertThat(singleMetrics.isStabImputed()).isTrue();
    assertThat(singleMetrics.rawStab()).isEqualTo(0.50);
    assertThat(singleMetrics.evidenceReliabilityM2()).isEqualTo(0.20);
  }

  @Test
  @DisplayName("Luồng 2: Fallback ER cho Facebook - Bài không views tính tương tác trung bình chia follower")
  void testEvidence_facebookFollowerErFallback() {
    // KOC Facebook 10.000 followers, có 2 bài không có views (ảnh/text), mỗi bài 200 tương tác
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
        creatorF, List.of(pF1)
    );

    List<NormalizedCandidateContext> results = orchestrator.execute(
        List.of(candA, candD, candF),
        campaign,
        Collections.emptySet(),
        postsMap
    );

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

  private RecommendationCandidateProjection mockCandidate(UUID id, String platform, Long followers) {
    RecommendationCandidateProjection c = mock(RecommendationCandidateProjection.class);
    when(c.getCreatorId()).thenReturn(id);
    when(c.getPlatform()).thenReturn(platform);
    when(c.getFollowers()).thenReturn(followers);
    when(c.getAvgViews()).thenReturn(BigDecimal.valueOf(10_000));
    when(c.getAvgLikes()).thenReturn(BigDecimal.valueOf(500));
    when(c.getEngagementRate()).thenReturn(BigDecimal.valueOf(5.0));
    return c;
  }
}
