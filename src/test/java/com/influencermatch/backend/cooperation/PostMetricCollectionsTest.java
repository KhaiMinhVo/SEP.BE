package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PostMetricCollectionsTest {
  final ObjectMapper json = new ObjectMapper();

  @Test void deployedResponsePreservesKnownCountersButNotAmbiguousZero() throws Exception {
    var result = PostMetricCollections.normalize(json.readTree("""
        {"status":"success","metrics":{"views":449044462,"likes":0,"comments":0,"shares":0}}
        """));
    assertThat(result.path("views").asLong()).isEqualTo(449044462);
    assertThat(result.has("likes")).isFalse();
    assertThat(result.has("clicks")).isFalse();
  }

  @Test void explicitAvailabilityCanDisambiguateActualZero() throws Exception {
    var result = PostMetricCollections.normalize(json.readTree("""
        {"status":"success","metrics":{"views":0},"metricAvailability":{"views":true}}
        """));
    assertThat(result.path("views").asLong()).isZero();
  }

  @Test void newMetricStatusContractDistinguishesTrueZeroAndMissingCounters() throws Exception {
    var result = PostMetricCollections.normalize(json.readTree("""
        {"status":"success","schemaVersion":"1.1","metrics":{"views":0,"likes":null},
         "metricStatus":{"views":"AVAILABLE","likes":"FETCH_FAILED"}}
        """));
    assertThat(result.path("views").asInt()).isZero();
    assertThat(result.has("likes")).isFalse();
    var invalid = json.readTree("""
        {"status":"success","metrics":{"views":5,"likes":0},"metricStatus":{"likes":"NOT_SUPPORTED"}}
        """ );
    assertThatThrownBy(() -> PostMetricCollections.normalize(invalid)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void aiErrorsAndNoMetricAvailabilityUseStableErrorCodes() throws Exception {
    var unavailable = json.readTree("""
        {"status":"success","metrics":{"views":null,"likes":null},
         "metricStatus":{"views":"NOT_SUPPORTED","likes":"FETCH_FAILED"}}
        """);
    assertThat(PostMetricCollections.allUnavailable(unavailable)).isTrue();

    var retryable = json.readTree("""
        {"status":"error","error":{"code":"RATE_LIMITED","retryable":true,"retryAfterSeconds":60}}
        """);
    assertThat(PostMetricCollections.aiErrorCode(retryable)).isEqualTo("RATE_LIMITED");

    var unknown = json.readTree("""
        {"status":"error","error":{"code":"NEW_UPSTREAM_CODE","retryable":false}}
        """);
    assertThat(PostMetricCollections.aiErrorCode(unknown)).isEqualTo("UNSUPPORTED_CRAWLER_RESPONSE");
  }

  @Test void errorsMalformedAndMissingCountersNeverBecomeSuccess() throws Exception {
    for (String raw : new String[]{"{}", "{\"status\":\"error\",\"metrics\":{\"views\":1}}",
        "{\"status\":\"success\",\"metrics\":{\"views\":-1}}",
        "{\"status\":\"success\",\"metrics\":{\"views\":\"2K\"}}",
        "{\"status\":\"success\",\"metrics\":{\"views\":0}}"}) {
      var body = json.readTree(raw);
      assertThatThrownBy(() -> PostMetricCollections.normalize(body)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test void rejectsUntrustedHostsAndPorts() {
    for (String url : new String[]{"http://www.tiktok.com/video/1", "https://tiktok.com.attacker.test/video/1",
        "https://127.0.0.1/post", "https://www.tiktok.com:8443/post", "https://user:pass@www.tiktok.com/post"})
      assertThatThrownBy(() -> PostMetricCollections.supportedUrl(url)).isInstanceOf(RuntimeException.class);
    assertThat(PostMetricCollections.supportedUrl("https://www.tiktok.com/@test/video/123"))
        .isEqualTo("https://www.tiktok.com/@test/video/123");
  }

  @Test void canonicalAndTrackedUrlsAreMatchedWithoutQueryParameters() throws Exception {
    var raw = json.readTree("""
        {"status":"success","postUrl":"https://www.tiktok.com/@lenkangoo/video/7649262001746758932?q=duet&t=1753286958619",
         "canonicalPostUrl":"https://www.tiktok.com/@lenkangoo/video/7649262001746758932",
         "author":{"externalId":"60191951471263744","username":"lenkangoo"}}
        """);
    assertThat(PostMetricCollections.publicationMatches(raw,
        "https://www.tiktok.com/@lenkangoo/video/7649262001746758932")).isTrue();
  }

  @Test void sanitizesCrawlerPayloadBeforeStorage() throws Exception {
    var raw = json.readTree("""
        {"status":"success","platform":"TIKTOK","author":{"externalId":"60191951471263744","username":"lenkangoo"},
         "content":{"postedAt":"2026-06-09T05:23:23Z","caption":"caption",
         "thumbnailUrl":"https://p16-sign.example/image~tplv-signature.jpg"},"trackedAt":"2026-11-22T08:00:37Z"}
        """);
    var safe = PostMetricCollections.sanitizedResponse(raw);
    assertThat(safe.path("author").isMissingNode()).isTrue();
    assertThat(safe.path("content").path("caption").asText()).isEqualTo("caption");
    assertThat(safe.path("content").has("thumbnailUrl")).isFalse();
    assertThat(safe.path("trackedAt").asText()).isEqualTo("2026-11-22T08:00:37Z");
  }
}
