package com.influencermatch.backend.creator.client;

import com.influencermatch.backend.creator.dto.CrawlerResolveAuthorResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    // SSR parsing can take 10-15s, set read timeout to 25s
    requestFactory.setReadTimeout(Duration.ofSeconds(25));

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

  /**
   * Resolves TikTok Author ID (permanent numeric ID) via Crawler SSR preflight.
   * Useful when KOC changed username (e.g. mixigaming -> domixi) before OAuth verification.
   *
   * @param username current TikTok username (e.g. "domixi")
   * @param expectedExternalId expected permanent ID stored in database (nullable)
   * @return CrawlerResolveAuthorResponse containing author_id and is_matched flag
   */
  public Optional<CrawlerResolveAuthorResponse> resolveTikTokAuthor(String username, String expectedExternalId) {
    if (username == null || username.isBlank()) {
      return Optional.empty();
    }
    String cleanUsername = username.replace("@", "").trim();
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("username", cleanUsername);
      if (expectedExternalId != null && !expectedExternalId.isBlank()) {
        body.put("expected_external_id", expectedExternalId.trim());
      }
      body.put("auto_update_staging", false);

      CrawlerResolveAuthorResponse response = restClient.post()
          .uri("/tiktok/resolve-author")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(CrawlerResolveAuthorResponse.class);

      return Optional.ofNullable(response);
    } catch (Exception e) {
      log.warn("Failed to resolve TikTok author ID via crawler for @{}: {}", cleanUsername, e.getMessage());
      return Optional.empty();
    }
  }
}
