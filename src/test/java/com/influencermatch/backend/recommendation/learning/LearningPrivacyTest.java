package com.influencermatch.backend.recommendation.learning;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.recommendation.dto.RecommendationRunView;
import org.junit.jupiter.api.Test;

class LearningPrivacyTest {
  @Test
  void runProjectionNeverReturnsUnknownInternalEvidenceOrJpaGraphs() throws Exception {
    var json = new ObjectMapper();
    var saved =
        json.readTree(
            """
[{"creatorId":"channel","finalScore":75,"otherBrandReview":"secret-review",
"campaign":{"brandProfile":{"user":{"passwordHash":"secret-hash"}}},
"scoreBreakdown":{"m1Score":80,"reviewIds":["secret-review"],"m4ReviewIds":["secret-other"],
"m1Details":{"scope":"MASTER_GENERAL","reviewEvidence":["secret-review"],
"review":{"score":80,"count":3,"brandCount":2,"privateCost":10000}}}}]
""");
    var value = RecommendationRunView.sanitize(saved);
    assertThat(value.get(0).path("finalScore").asInt()).isEqualTo(75);
    assertThat(
            value
                .get(0)
                .path("scoreBreakdown")
                .path("m1Details")
                .path("review")
                .path("brandCount")
                .asInt())
        .isEqualTo(2);
    assertThat(value.toString())
        .doesNotContain(
            "secret", "password", "privateCost", "reviewIds", "reviewEvidence", "campaign");
    assertThat(saved.toString())
        .contains("secret-review"); // projection never rewrites historical input
  }
}
