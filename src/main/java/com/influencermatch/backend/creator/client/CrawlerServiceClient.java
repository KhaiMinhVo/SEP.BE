package com.influencermatch.backend.creator.client;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class CrawlerServiceClient {

  private final RestClient restClient;

  public CrawlerServiceClient(
      @Value("${crawler.service.url:http://localhost:8000/api/v1/crawl}") String crawlerServiceUrl) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(Duration.ofSeconds(5));

    this.restClient =
        RestClient.builder()
            .baseUrl(crawlerServiceUrl)
            .requestFactory(requestFactory)
            .build();
    log.info("Initialized CrawlerServiceClient with endpoint: {}", crawlerServiceUrl);
  }

  public void triggerShortlistCrawlAsync(String platform, String username, UUID creatorId, UUID campaignId) {
    CompletableFuture.runAsync(() -> {
      try {
        Map<String, Object> body = Map.of(
            "platform", platform != null ? platform.trim().toUpperCase() : "UNKNOWN",
            "username", username != null ? username : "",
            "creatorId", creatorId != null ? creatorId.toString() : "",
            "campaignId", campaignId != null ? campaignId.toString() : ""
        );
        restClient.post()
            .uri("/shortlist-trigger")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toBodilessEntity();
        log.info("Successfully triggered crawl for shortlisted creator @{} ({})", username, platform);
      } catch (Exception e) {
        log.warn("Failed to trigger crawler for @{}: {}", username, e.getMessage());
      }
    });
  }
}