package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

class PostMetricsPostgresIntegrationTest extends ExecutionScenarioSupport {
  @MockBean PostMetricsClient postMetrics;

  String metricsPath() throws Exception { return root + "/deliverables/" + unit() + "/post-metrics"; }

  void published() throws Exception { submit(); approve(); publish(false); }

  void crawlerResponse() throws Exception {
    when(postMetrics.collect(anyString())).thenReturn(json.readTree("""
        {"status":"success","postUrl":"https://www.tiktok.com/@test/video/123",
         "metrics":{"views":1000,"likes":80,"comments":10,"shares":10}}
        """));
  }

  @Test void collectionIsIdempotentAndDoesNotChangeOutcomeOrExecutionRevision() throws Exception {
    published(); crawlerResponse();
    long before = revision();
    String key = UUID.randomUUID().toString();
    var body = Map.of("expectedRevision", before);
    var first = request(brand, metricsPath(), body, 200, key);
    assertThat(first.get(0).path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(first.get(0).path("metrics").path("views").asInt()).isEqualTo(1000);
    assertThat(first.get(0).has("rawResponse")).isFalse();
    request(brand, metricsPath(), body, 200, key);
    verify(postMetrics, times(1)).collect(anyString());
    assertThat(revision()).isEqualTo(before);
    assertThat(state().path("outcomeRevisions").size()).isZero();
    request(brand, metricsPath(), body, 409);
    assertThat(call(owner, get(metricsPath()), null, 200).path("data").size()).isEqualTo(1);
  }

  @Test void onlyAuthorizedCurrentPublicationCanBeCollected() throws Exception {
    request(brand, metricsPath(), Map.of("expectedRevision", revision()), 404);
    published();
    var outsider = account(com.influencermatch.backend.user.enums.Role.BRAND);
    request(outsider, metricsPath(), Map.of("expectedRevision", revision()), 403);
    request(owner, metricsPath(), Map.of("expectedRevision", revision() - 1), 409);
    verifyNoInteractions(postMetrics);
  }

  @Test void failedCrawlerPreservesExistingResultAndEvidenceHistory() throws Exception {
    ready();
    long outcome = latestOutcome();
    when(postMetrics.collect(anyString())).thenThrow(new RuntimeException("upstream timeout"));
    var rows = request(brand, metricsPath(), Map.of("expectedRevision", revision()), 200);
    assertThat(rows.get(0).path("status").asText()).isEqualTo("FAILED");
    assertThat(rows.get(0).path("errorCode").asText()).isEqualTo("CRAWLER_UNAVAILABLE");
    assertThat(latestOutcome()).isEqualTo(outcome);
    assertThat(state().path("status").asText()).isEqualTo("IN_PROGRESS");
  }

  @Test void creatorAdoptsServerEvidenceAndBrandCannotSubmitKpi() throws Exception {
    published(); crawlerResponse();
    var rows = request(brand, metricsPath(), Map.of("expectedRevision", revision()), 200);
    var post = new LinkedHashMap<String, Object>(measurement());
    post.put("collectionId", rows.get(0).path("id").asText());
    var body = Map.of("expectedRevision", revision(), "posts", List.of(post));
    request(brand, root + "/outcome", body, 403);
    request(owner, root + "/outcome", body, 200);
    var saved = state().path("outcomeRevisions").get(0).path("payload").path("posts").get(0);
    assertThat(saved.path("metricSources").path("views").asText()).isEqualTo("PUBLIC_POST_CRAWL");
    assertThat(saved.path("collectedAt").asText()).isNotBlank();
    post.put("views", 999999);
    request(owner, root + "/outcome", Map.of("expectedRevision", revision(), "reason", "edited", "posts", List.of(post)), 400);
    assertThat(latestOutcome()).isEqualTo(1);
  }

  @Test void trackingAfterAcceptanceCannotMutateAcceptedOutcome() throws Exception {
    ready(); accept(); crawlerResponse();
    var before = state().deepCopy();
    var rows = request(brand, metricsPath(), Map.of("expectedRevision", revision()), 200);
    assertThat(rows.get(0).path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(state().path("outcomeRevisions")).isEqualTo(before.path("outcomeRevisions"));
    assertThat(state().path("acceptances")).isEqualTo(before.path("acceptances"));
    assertThat(state().path("status").asText()).isEqualTo("AWAITING_REVIEW");
    request(owner, root + "/outcome", Map.of("expectedRevision", revision(), "posts", List.of(measurement())), 409);
  }
}
