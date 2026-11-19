package com.influencermatch.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.RecommendationRun;
import com.influencermatch.backend.recommendation.dto.RecommendationRunView;
import com.influencermatch.backend.recommendation.engine.critic.DynamicWeightBlender;
import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecommendationRunViewTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final DynamicWeightBlender blender = new DynamicWeightBlender();

  @Test
  void preservesDevelopPresentationWithoutReturningPrivateHistory() throws Exception {
    var snapshot =
        mapper.readTree(
            """
            [{"creatorId":"public-id","priceStatus":"PRICE_UNKNOWN","scoreReady":true,
              "provisional":false,"privateContact":"hidden",
              "scoreBreakdown":{"m1Score":50,"m2Score":75,"m3Score":80,"m4Score":60,
                                "reviewIds":["private-review"]},
              "reasoning":{"summary":"Public explanation","reasons":[],
                           "evidenceWarnings":["limited evidence"],"proofUrl":"hidden"}}]
            """);
    var publicRow = RecommendationRunView.sanitize(snapshot).get(0);
    assertThat(publicRow.path("priceStatus").asText()).isEqualTo("PRICE_UNKNOWN");
    assertThat(publicRow.path("scoreReady").asBoolean()).isTrue();
    assertThat(publicRow.path("provisional").asBoolean()).isFalse();
    assertThat(publicRow.path("scoreBreakdown").path("m3Score").asDouble()).isEqualTo(80);
    assertThat(publicRow.path("reasoning").path("evidenceWarnings").get(0).asText())
        .isEqualTo("limited evidence");
    assertThat(publicRow.has("privateContact")).isFalse();
    assertThat(publicRow.path("scoreBreakdown").has("reviewIds")).isFalse();
    assertThat(publicRow.path("reasoning").has("proofUrl")).isFalse();
  }

  @Test
  @DisplayName("Legacy Run: Array snapshot returns null for runMetadata without failing")
  void testExtractRunMetadata_LegacyRun_ReturnsNull() throws Exception {
    JsonNode legacySnapshot = mapper.readTree("""
        [{"creatorId":"00000000-0000-0000-0000-000000000001","finalScore":85.0}]
        """);

    RecommendationRun run = RecommendationRun.builder()
        .id(UUID.randomUUID())
        .campaign(Campaign.builder().id(UUID.randomUUID()).build())
        .brandProfile(BrandProfile.builder().id(UUID.randomUUID()).build())
        .contextVersion(1)
        .scoringPolicyVersion("v1")
        .scoringSnapshot(legacySnapshot)
        .status("COMPLETED")
        .createdAt(LocalDateTime.now())
        .build();

    RecommendationRunView view = RecommendationRunView.from(run);
    assertThat(view.runMetadata()).isNull();
    assertThat(view.scoringSnapshot()).isNotNull();
    assertThat(view.scoringSnapshot().isArray()).isTrue();
    assertThat(view.scoringSnapshot().size()).isEqualTo(1);
  }

  @Test
  @DisplayName("V2 Run: JSONB Object snapshot extracts runMetadata and preserves weights without recomputation")
  void testExtractRunMetadata_V2Run_ReturnsMetadataWithoutRecomputation() throws Exception {
    ObjectNode snapshot = mapper.createObjectNode();
    snapshot.put("schemaVersion", "2.0");

    ObjectNode meta = snapshot.putObject("runMetadata");
    meta.put("scoringAsOf", "2026-11-19T10:00:00");
    meta.put("historyCutoff", "2024-11-19T10:00:00");
    meta.putArray("effectiveWeights").add(0.35).add(0.25).add(0.20).add(0.20);
    meta.putArray("businessWeights").add(0.40).add(0.20).add(0.20).add(0.20);
    meta.putArray("criticWeights").add(0.25).add(0.35).add(0.20).add(0.20);
    meta.put("lambdaUsed", 0.30);
    meta.put("weightingMode", "DYNAMIC_BLENDED");
    meta.put("rankingMode", "BEST_MATCH");
    meta.put("mainPoolSize", 10);
    meta.put("provisionalPoolSize", 3);
    meta.put("excludedCount", 1);

    snapshot.putArray("candidates")
        .addObject()
        .put("creatorId", "00000000-0000-0000-0000-000000000001")
        .put("finalScore", 92.5);

    RecommendationRun run = RecommendationRun.builder()
        .id(UUID.randomUUID())
        .campaign(Campaign.builder().id(UUID.randomUUID()).build())
        .brandProfile(BrandProfile.builder().id(UUID.randomUUID()).build())
        .contextVersion(2)
        .scoringPolicyVersion("m4-evidence-v1")
        .scoringSnapshot(snapshot)
        .status("COMPLETED")
        .createdAt(LocalDateTime.now())
        .build();

    RecommendationRunView view = RecommendationRunView.from(run);
    assertThat(view.runMetadata()).isNotNull();
    assertThat(view.runMetadata().get("weightingMode").asText()).isEqualTo("DYNAMIC_BLENDED");
    assertThat(view.runMetadata().get("lambdaUsed").asDouble()).isEqualTo(0.30);
    assertThat(view.runMetadata().get("effectiveWeights").get(0).asDouble()).isEqualTo(0.35);
    assertThat(view.scoringPolicyVersion()).isEqualTo("m4-evidence-v1");
  }

  @Test
  @DisplayName("Weight Blending: effectiveWeights = (1 - lambda) * business + lambda * critic under DYNAMIC_BLENDED")
  void testWeightBlending_DynamicBlended_FormulaAndUnitSum() {
    double[] criticWeights = new double[] {0.20, 0.40, 0.20, 0.20};
    double lambda = 0.30;
    String objective = "AWARENESS";

    EngineWeights weights = blender.blend(objective, Optional.of(criticWeights), lambda);

    assertThat(weights.mode()).isEqualTo("CRITIC_BLENDED");
    assertThat(weights.lambdaUsed()).isEqualTo(lambda);
    assertThat(weights.criticWeights()).containsExactly(criticWeights);

    double[] b = weights.businessWeights();
    double expectedW1 = (1.0 - lambda) * b[0] + lambda * criticWeights[0];
    double expectedW2 = (1.0 - lambda) * b[1] + lambda * criticWeights[1];
    double expectedW3 = (1.0 - lambda) * b[2] + lambda * criticWeights[2];
    double expectedW4 = (1.0 - lambda) * b[3] + lambda * criticWeights[3];
    double sum = expectedW1 + expectedW2 + expectedW3 + expectedW4;

    assertThat(weights.w1()).isCloseTo(expectedW1 / sum, offset(1e-4));
    assertThat(weights.w2()).isCloseTo(expectedW2 / sum, offset(1e-4));
    assertThat(weights.w3()).isCloseTo(expectedW3 / sum, offset(1e-4));
    assertThat(weights.w4()).isCloseTo(expectedW4 / sum, offset(1e-4));

    // Unit sum
    assertThat(weights.w1() + weights.w2() + weights.w3() + weights.w4()).isCloseTo(1.0, offset(1e-4));
  }

  @Test
  @DisplayName("Weight Blending Fallback: W equals businessWeights when criticWeights is empty or lambda == 0")
  void testWeightBlending_Fallback_EqualsBusinessWeights() {
    String objective = "CONVERSION";
    double[] b = blender.resolveBusinessWeights(objective);

    EngineWeights weights = blender.blend(objective, Optional.empty(), 0.30);

    assertThat(weights.mode()).isEqualTo("BUSINESS_FALLBACK");
    assertThat(weights.lambdaUsed()).isEqualTo(0.0);
    assertThat(weights.criticWeights()).isNull();
    assertThat(weights.w1()).isEqualTo(b[0]);
    assertThat(weights.w2()).isEqualTo(b[1]);
    assertThat(weights.w3()).isEqualTo(b[2]);
    assertThat(weights.w4()).isEqualTo(b[3]);
    assertThat(weights.w1() + weights.w2() + weights.w3() + weights.w4()).isCloseTo(1.0, offset(1e-4));
  }

  @Test
  @DisplayName("Dimensions Order and Range: W has exactly 4 elements [w1..w4] in [0, 1] matching M1..M4")
  void testFourDimensions_M1ToM4_BoundsAndOrder() {
    for (String obj : new String[] {"AWARENESS", "ENGAGEMENT", "TRAFFIC", "CONVERSION", "COMMUNITY_GROWTH"}) {
      double[] b = blender.resolveBusinessWeights(obj);
      assertThat(b).hasSize(4);
      for (int i = 0; i < 4; i++) {
        assertThat(b[i]).isBetween(0.0, 1.0);
      }
      assertThat(b[0] + b[1] + b[2] + b[3]).isCloseTo(1.0, offset(1e-4));
    }
  }
}
