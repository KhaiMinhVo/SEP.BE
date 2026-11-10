package com.influencermatch.backend.campaign.history;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class CampaignHistoryCalculatorTest {
  final ObjectMapper json = new ObjectMapper();

  CampaignHistoryCalculator.Source source(String post, long views) throws Exception {
    var data =
        json.readTree(
            """
{"currency":"VND","financialConfirmed":true,"costs":[{"amount":1000}],"gmv":5000,"revenue":3000,
 "periodStart":"2026-11-01T00:00:00Z","periodEnd":"2026-11-02T00:00:00Z",
 "posts":[{"deliverableId":"unit","submissionRevision":1,"views":1000,"clicks":25,"likes":80,"comments":10,"shares":10,
 "periodStart":"2026-11-01T00:00:00Z","periodEnd":"2026-11-02T00:00:00Z"}]}
""");
    ((ObjectNode) data.path("posts").get(0)).put("views", views);
    return new CampaignHistoryCalculator.Source(
        UUID.randomUUID(),
        "COMPLETED",
        new BigDecimal("10000"),
        "VND",
        true,
        data,
        json.readTree("{\"actualCost\":1000,\"formulaVersion\":\"outcome-finance-v1\"}"),
        Map.of("unit:1", post));
  }

  @Test
  void singleScopeUsesTotalsAndKeepsCommitmentSeparate() throws Exception {
    var r = CampaignHistoryCalculator.calculate(List.of(source("TIKTOK:123", 1000)));
    assertThat(r.metrics().get("actualCost").value()).isEqualByComparingTo("1000");
    assertThat(r.metrics().get("committedFee").value()).isEqualByComparingTo("10000");
    assertThat(r.metrics().get("roas").value()).isEqualByComparingTo("5");
    assertThat(r.metrics().get("roi").value()).isEqualByComparingTo("2");
    assertThat(r.metrics().get("cpm").value()).isEqualByComparingTo("1000");
    assertThat(r.metrics().get("cpe").value()).isEqualByComparingTo("10");
  }

  @Test
  void repeatedPostCountsOnceAndRevenueNeverSummedWithoutAttribution() throws Exception {
    var r =
        CampaignHistoryCalculator.calculate(List.of(source("same", 1000), source("same", 1000)));
    assertThat(r.metrics().get("views").value()).isEqualByComparingTo("1000");
    assertThat(r.metrics().get("actualCost").value()).isEqualByComparingTo("2000");
    assertThat(r.metrics().get("cpm").value()).isEqualByComparingTo("2000");
    for (String m : List.of("gmv", "revenue", "roas", "roi")) {
      assertThat(r.metrics().get(m).value()).isNull();
      assertThat(r.metrics().get(m).unavailableReasons())
          .contains("ATTRIBUTION_NOT_ESTABLISHED")
          .doesNotContain("MISSING_FINANCIAL_INPUT");
    }
  }

  @Test
  void conflictOnlyInvalidatesAffectedMetrics() throws Exception {
    var r =
        CampaignHistoryCalculator.calculate(List.of(source("same", 1000), source("same", 1200)));
    assertThat(r.metrics().get("views").value()).isNull();
    assertThat(r.metrics().get("views").unavailableReasons()).contains("DUPLICATE_POST_CONFLICT");
    assertThat(r.metrics().get("cpm").value()).isNull();
    assertThat(r.metrics().get("engagements").value()).isEqualByComparingTo("100");
  }

  @Test
  void incompatiblePeriodsAndMissingInputsRemainUnavailable() throws Exception {
    var a = source("one", 1000);
    var b = source("two", 1000);
    ((ObjectNode) b.payload().path("posts").get(0))
        .put("periodEnd", "2026-11-03T00:00:00Z")
        .putNull("clicks");
    var r = CampaignHistoryCalculator.calculate(List.of(a, b));
    assertThat(r.metrics().get("views").unavailableReasons())
        .contains("INCONSISTENT_MEASUREMENT_PERIOD");
    assertThat(r.metrics().get("clicks").unavailableReasons()).contains("MISSING_METRIC_INPUT");
    assertThat(r.metrics().get("cpm").unavailableReasons())
        .contains("INCONSISTENT_FINANCIAL_PERIOD");
  }

  @Test
  void legacyCompletedIsNotSilentlyReportedAsZero() {
    var s =
        new CampaignHistoryCalculator.Source(
            UUID.randomUUID(),
            "COMPLETED",
            null,
            "USD",
            false,
            json.nullNode(),
            json.nullNode(),
            Map.of());
    var r = CampaignHistoryCalculator.calculate(List.of(s));
    assertThat(r.eligibleCount()).isZero();
    assertThat(r.missingSourceCollaborationIds()).containsExactly(s.id());
    assertThat(r.metrics().get("actualCost").value()).isNull();
    assertThat(r.metrics().get("actualCost").unavailableReasons())
        .contains("MISSING_ACCEPTED_REVIEWED_SOURCE");
    assertThat(r.metrics().get("committedFee").unavailableReasons())
        .contains("UNSUPPORTED_CURRENCY", "MISSING_COMMITMENT");
  }

  @Test
  void unfinishedAndCancelledNeverBecomeSuccess() throws Exception {
    var s = source("post", 1000);
    var unfinished =
        new CampaignHistoryCalculator.Source(
            s.id(),
            "AWAITING_REVIEW",
            s.agreedFee(),
            s.currency(),
            true,
            s.payload(),
            s.calculation(),
            s.publicationKeys());
    var cancelled =
        new CampaignHistoryCalculator.Source(
            UUID.randomUUID(),
            "CANCELLED",
            null,
            null,
            false,
            json.nullNode(),
            json.nullNode(),
            Map.of());
    var r = CampaignHistoryCalculator.calculate(List.of(unfinished, cancelled));
    assertThat(r.completedCount()).isZero();
    assertThat(r.cancelledCount()).isEqualTo(1);
    assertThat(r.unfinishedCount()).isEqualTo(1);
    assertThat(r.metrics().get("actualCost").value()).isNull();
    assertThat(r.metrics().get("committedFee").value()).isEqualByComparingTo("10000");
  }

  @Test
  void zeroDenominatorAndCurrencyDoNotProduceRatios() throws Exception {
    var s = source("post", 0);
    ((ObjectNode) s.payload()).put("currency", "USD");
    var r = CampaignHistoryCalculator.calculate(List.of(s));
    assertThat(r.metrics().get("actualCost").unavailableReasons()).contains("UNSUPPORTED_CURRENCY");
    assertThat(r.metrics().get("cpm").unavailableReasons()).contains("INVALID_DENOMINATOR");
  }

  @Test
  void whitelistDoesNotLeakExecutionOrFillSnapshotGaps() throws Exception {
    var source =
        json.readTree(
            """
{"name":"Frozen","budgetMin":30,"feedback":"private","embedding":[1],"creatorIds":["secret"],
 "deliverables":[{"platform":"TIKTOK","contentType":"Review","quantity":1,"creatorId":"secret","dueAt":"old"}]}
""");
    var brief = HistoryBrief.clean(json, source);
    assertThat(brief.path("name").asText()).isEqualTo("Frozen");
    assertThat(brief.get("totalBudget").isNull()).isTrue();
    assertThat(
            brief.has("budgetMin")
                || brief.has("feedback")
                || brief.has("embedding")
                || brief.has("creatorIds"))
        .isFalse();
    assertThat(brief.path("deliverables").get(0).has("creatorId")).isFalse();
    assertThat(HistoryBrief.missing(source)).contains("totalBudget", "startDate");
  }
}
