package com.influencermatch.backend.creator.client;

import com.influencermatch.backend.creator.dto.PostMetricsDto;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CrawlerPostMetricsTest {

  private static final String SAMPLE_SUCCESS_JSON = """
      {
        "schemaVersion": "1.1",
        "status": "success",
        "platform": "TIKTOK",
        "postUrl": "https://www.tiktok.com/@schannelvn/video/74382910291?tracking=123",
        "canonicalPostUrl": "https://www.tiktok.com/@schannelvn/video/74382910291",
        "postId": "74382910291",
        "author": {
          "externalId": "6631682124809076737",
          "username": "schannelvn",
          "displayName": "SChannel Official"
        },
        "metrics": {
          "views": 150000,
          "likes": 0,
          "comments": null,
          "shares": 80,
          "engagementRate": 8.69
        },
        "metricStatus": {
          "views": "AVAILABLE",
          "likes": "AVAILABLE",
          "comments": "FETCH_FAILED",
          "shares": "AVAILABLE"
        },
        "metricErrors": {
          "comments": { "code": "COUNTER_UNAVAILABLE", "retryable": true }
        },
        "content": {
          "caption": "Trai nghiem Galaxy S26 Ultra",
          "thumbnailUrl": "https://example.com/thumb.jpeg",
          "postedAt": "2026-11-20T05:23:23Z"
        },
        "trackedAt": "2026-11-22T01:00:00Z",
        "servedAt": "2026-11-22T01:02:00Z",
        "collection": {
          "source": "PUBLIC_PAGE",
          "method": "SSR",
          "cacheHit": false,
          "scope": "LIFETIME_AT_COLLECTION",
          "engagementRateFormula": "(likes + comments + shares) / views * 100",
          "engagementRateUnit": "PERCENT"
        },
        "warnings": []
      }
      """;

  @Test
  void testFetchPostMetrics_DeserializationAndCounterZeroPreserved() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://crawler.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    server.expect(requestTo("http://crawler.test/post-metrics"))
        .andExpect(method(org.springframework.http.HttpMethod.POST))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json("{\"post_url\":\"https://www.tiktok.com/@schannelvn/video/74382910291\"}"))
        .andRespond(withSuccess(SAMPLE_SUCCESS_JSON, MediaType.APPLICATION_JSON));

    CrawlerServiceClient client = new CrawlerServiceClient(builder.build());
    Optional<PostMetricsDto.PostMetricsResponse> responseOpt =
        client.fetchPostMetrics("https://www.tiktok.com/@schannelvn/video/74382910291");

    assertTrue(responseOpt.isPresent());
    PostMetricsDto.PostMetricsResponse res = responseOpt.get();

    assertEquals("1.1", res.schemaVersion());
    assertEquals("success", res.status());
    assertEquals("TIKTOK", res.platform());
    assertEquals("74382910291", res.postId());
    assertEquals("schannelvn", res.author().username());

    // Counter 0 must be preserved as 0L (not null or omitted)
    assertEquals(150000L, res.getSafeViews());
    assertEquals(0L, res.getSafeLikes());
    assertNull(res.getSafeComments(), "Missing/failed counter must be null");
    assertEquals(80L, res.getSafeShares());

    server.verify();
  }

  @Test
  void testFetchPostMetrics_InvalidUrlReturnsEmpty() {
    CrawlerServiceClient client = new CrawlerServiceClient(RestClient.builder().build());
    assertTrue(client.fetchPostMetrics(null).isEmpty());
    assertTrue(client.fetchPostMetrics("   ").isEmpty());
  }
}
