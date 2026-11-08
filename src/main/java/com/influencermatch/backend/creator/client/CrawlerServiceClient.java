package com.influencermatch.backend.creator.client;

import com.influencermatch.backend.creator.dto.CrawlerResolveAuthorResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

  @org.springframework.beans.factory.annotation.Autowired
  public CrawlerServiceClient(
      @Value("${crawler.service.url:http://localhost:8000/api/v1/crawl}")
          String crawlerServiceUrl) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    // SSR parsing can take 10-15s, set read timeout to 25s
    requestFactory.setReadTimeout(Duration.ofSeconds(25));

    this.restClient =
        RestClient.builder().baseUrl(crawlerServiceUrl).requestFactory(requestFactory).build();
    log.info("Initialized CrawlerServiceClient with endpoint: {}", crawlerServiceUrl);
  }

  public CrawlerServiceClient(RestClient restClient) {
    this.restClient = restClient;
  }

  /** Synchronous transport only; the after-commit dispatcher supplies the bounded executor. */
  public void requestShortlistCrawl(
      String platform, String username, UUID creatorId, UUID campaignId) {
    var response =
        restClient
            .post()
            .uri("/shortlist-trigger")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "platform",
                    platform == null
                        ? "UNKNOWN"
                        : platform.trim().toUpperCase(java.util.Locale.ROOT),
                    "username",
                    username == null ? "" : username,
                    "creatorId",
                    creatorId.toString(),
                    "campaignId",
                    campaignId.toString()))
            .retrieve()
            .toBodilessEntity();
    if (!response.getStatusCode().is2xxSuccessful())
      throw new IllegalStateException("Crawler did not accept request");
  }

  /**
   * Resolves TikTok Author ID (permanent numeric ID) via Crawler SSR preflight. Useful when KOC
   * changed username (e.g. mixigaming -> domixi) before OAuth verification.
   *
   * @param username current TikTok username (e.g. "domixi")
   * @param expectedExternalId expected permanent ID stored in database (nullable)
   * @return CrawlerResolveAuthorResponse containing author_id and is_matched flag
   */
  public Optional<CrawlerResolveAuthorResponse> resolveTikTokAuthor(
      String username, String expectedExternalId) {
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

      CrawlerResolveAuthorResponse response =
          restClient
              .post()
              .uri("/tiktok/resolve-author")
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(CrawlerResolveAuthorResponse.class);

      return Optional.ofNullable(response);
    } catch (Exception e) {
      log.warn(
          "Failed to resolve TikTok author ID via crawler for @{}: {}",
          cleanUsername,
          e.getMessage());
      return Optional.empty();
    }
  }

  /** Strict identity-only request used by OAuth; never queues crawl or changes staging. */
  public CrawlerResolveAuthorResponse resolveTikTokIdentity(String username) {
    try {
      return restClient
          .post()
          .uri("/tiktok/resolve-author")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("username", username, "auto_update_staging", false))
          .retrieve()
          .body(CrawlerResolveAuthorResponse.class);
    } catch (Exception ex) {
      throw new com.influencermatch.backend.exception.BusinessException(
          com.influencermatch.backend.exception.ErrorCode.TIKTOK_IDENTITY_RESOLUTION_FAILED,
          "Unable to verify TikTok identity; start a new connection");
    }
  }
}
