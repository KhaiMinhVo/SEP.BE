package com.influencermatch.backend.creator.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DatabaseNormalizationClientTest {
  @Test void previewAndApplyForwardTheServiceKeyAndAuthenticatedActor() {
    var builder = RestClient.builder().baseUrl("http://crawler.test");
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = new CrawlerServiceClient(builder.build());
    ReflectionTestUtils.setField(client, "normalizationApiKey", "normalization-test-key");
    var actor = UUID.randomUUID();
    String plan = "normalize-" + "a".repeat(32);
    server.expect(requestTo("http://crawler.test/db-normalization/preview"))
        .andExpect(header("X-Service-Key", "normalization-test-key"))
        .andExpect(content().json("{\"limit\":20}"))
        .andRespond(withSuccess("{\"status\":\"PREVIEW\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo("http://crawler.test/db-normalization/apply"))
        .andExpect(header("X-Service-Key", "normalization-test-key"))
        .andExpect(content().json("{\"plan_id\":\"" + plan + "\",\"actor_id\":\"" + actor + "\"}"))
        .andRespond(withSuccess("{\"status\":\"APPLIED\"}", MediaType.APPLICATION_JSON));
    assertThat(client.previewDatabaseNormalization(Map.of("limit", 20))).isPresent();
    assertThat(client.applyDatabaseNormalization(plan, actor)).isPresent();
    server.verify();
  }

  @Test void stalePlanErrorsAreNotReportedAsSuccessfulApplication() {
    var builder = RestClient.builder().baseUrl("http://crawler.test");
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = new CrawlerServiceClient(builder.build());
    ReflectionTestUtils.setField(client, "normalizationApiKey", "test-key");
    server.expect(requestTo("http://crawler.test/db-normalization/apply"))
        .andRespond(withStatus(HttpStatus.CONFLICT).body("expired"));
    assertThat(client.applyDatabaseNormalization("normalize-" + "a".repeat(32), UUID.randomUUID()).orElseThrow())
        .containsEntry("status", "error").containsEntry("upstreamStatus", 409);
    server.verify();
  }

  @Test void contentReclassificationCannotRequestWritesThroughTheProxy() {
    var builder = RestClient.builder().baseUrl("http://crawler.test");
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = new CrawlerServiceClient(builder.build());
    ReflectionTestUtils.setField(client, "normalizationApiKey", "test-key");
    server.expect(requestTo("http://crawler.test/batch-reclassify"))
        .andExpect(header("X-Service-Key", "test-key"))
        .andExpect(content().json("{\"mode\":\"RECLASSIFY\",\"dry_run\":true,\"limit\":5}"))
        .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
    client.reclassifyStoredDatabaseContent(Map.of("dry_run", false, "mode", "NORMALIZE"));
    server.verify();
  }
}
