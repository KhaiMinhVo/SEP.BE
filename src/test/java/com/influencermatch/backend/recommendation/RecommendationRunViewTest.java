package com.influencermatch.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.recommendation.dto.RecommendationRunView;
import org.junit.jupiter.api.Test;

class RecommendationRunViewTest {
  @Test
  void preservesDevelopPresentationWithoutReturningPrivateHistory() throws Exception {
    var mapper = new ObjectMapper();
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
}
