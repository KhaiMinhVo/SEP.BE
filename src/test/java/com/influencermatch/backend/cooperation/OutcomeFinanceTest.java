package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OutcomeFinanceTest {
  final ObjectMapper json = new ObjectMapper();

  JsonNode input() {
    return json.valueToTree(
        Map.of(
            "costs",
            List.of(Map.of("description", "Production", "amount", 1000)),
            "currency",
            "VND",
            "gmv",
            5000,
            "revenue",
            3000,
            "periodStart",
            "2026-11-01T00:00:00Z",
            "periodEnd",
            "2026-11-02T00:00:00Z",
            "posts",
            List.of(
                Map.of(
                    "periodStart",
                    "2026-11-01T00:00:00Z",
                    "periodEnd",
                    "2026-11-02T00:00:00Z",
                    "views",
                    2000,
                    "likes",
                    80,
                    "comments",
                    10,
                    "shares",
                    10))));
  }

  @Test
  void separatesRoiRoasAndExplicitActualCost() {
    var r = OutcomeFinance.calculate(json, input());
    assertThat(r.path("roas").decimalValue()).isEqualByComparingTo("5");
    assertThat(r.path("roi").decimalValue()).isEqualByComparingTo("2");
    assertThat(r.path("cpm").decimalValue()).isEqualByComparingTo("500");
    assertThat(r.path("cpe").decimalValue()).isEqualByComparingTo("10");
  }

  @Test
  void absentMetricDoesNotBecomeZero() {
    var p = input();
    ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("posts").get(0)).remove("shares");
    var r = OutcomeFinance.calculate(json, p);
    assertThat(r.path("cpe").isNull()).isTrue();
    assertThat(r.path("shares").isNull()).isTrue();
    assertThat(r.path("cpeUnavailableReason").asText()).isEqualTo("MISSING_INPUT");
  }

  @Test
  void inconsistentPeriodsAreUnavailable() {
    var p = input();
    ((com.fasterxml.jackson.databind.node.ObjectNode) p).put("periodEnd", "2026-11-03T00:00:00Z");
    assertThat(OutcomeFinance.calculate(json, p).path("roasUnavailableReason").asText())
        .isEqualTo("INCONSISTENT_MEASUREMENT_PERIOD");
  }

  @Test
  void zeroCostDoesNotClaimFreeSuccess() {
    var p = input();
    ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("costs").get(0)).put("amount", 0);
    assertThat(OutcomeFinance.calculate(json, p).path("roas").isNull()).isTrue();
  }

  @Test
  void rejectsInvalidMoneyAndFractionalMetrics() {
    var p = input();
    ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("posts").get(0)).put("views", 1.2);
    assertThatThrownBy(() -> OutcomeFinance.calculate(json, p))
        .isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
  }

  @Test
  void postTrackingDeduplicatesButContentQueryIsRetained() {
    assertThat(
            CollaborationExecution.postKey(
                "TIKTOK", "https://www.tiktok.com/@name/video/123/?utm_source=test", null))
        .isEqualTo(
            CollaborationExecution.postKey(
                "TIKTOK", "https://www.tiktok.com/@name/video/123", null));
    assertThat(CollaborationExecution.postKey("FACEBOOK", "https://facebook.com/watch?v=12", null))
        .isNotEqualTo(
            CollaborationExecution.postKey("FACEBOOK", "https://facebook.com/watch?v=13", null));
  }

  @Test
  void explicitPostIdAndUrlIdentifyTheSamePostWithoutTrustingClientAliases() {
    assertThat(
            CollaborationExecution.postKey(
                "TIKTOK", "https://www.tiktok.com/@test/video/123", null))
        .isEqualTo(
            CollaborationExecution.postKey(
                "TIKTOK", "https://www.tiktok.com/@other/video/123?utm_source=foo", "123"));
    assertThatThrownBy(
            () ->
                CollaborationExecution.postKey(
                    "TIKTOK", "https://www.tiktok.com/@test/video/123", "999"))
        .isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
  }

  @Test
  void fractionalRevisionIsRejectedRatherThanTruncated() {
    assertThatThrownBy(
            () ->
                json.readValue(
                    "{\"expectedRevision\":1.5,\"url\":\"https://content.test\",\"source\":\"CREATOR_PORTAL\"}",
                    ExecutionDtos.Submission.class))
        .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
  }

  @Test
  void negativeRoiRemainsNegativeWithoutClipping() {
    var data = input();
    ((com.fasterxml.jackson.databind.node.ObjectNode) data).put("revenue", 500);
    assertThat(OutcomeFinance.calculate(json, data).path("roi").decimalValue())
        .isEqualByComparingTo("-0.5");
  }

  @Test
  void equivalentTimezoneOffsetsDescribeTheSameMeasurementPeriod() {
    var data = input();
    ((com.fasterxml.jackson.databind.node.ObjectNode) data)
        .put("periodStart", "2026-11-01T07:00:00+07:00")
        .put("periodEnd", "2026-11-02T07:00:00+07:00");
    assertThat(OutcomeFinance.calculate(json, data).path("roas").decimalValue())
        .isEqualByComparingTo("5");
  }

  @Test
  void malformedFinancialPeriodIsRejected() {
    var data = input();
    ((com.fasterxml.jackson.databind.node.ObjectNode) data).put("periodStart", "not-a-date");
    assertThatThrownBy(() -> OutcomeFinance.calculate(json, data))
        .isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
  }
}
