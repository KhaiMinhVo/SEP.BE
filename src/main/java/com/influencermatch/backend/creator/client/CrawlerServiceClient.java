package com.influencermatch.backend.creator.client;

import com.influencermatch.backend.creator.dto.CrawlerResolveAuthorResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
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
  private final RestClient monitorRestClient;

  @org.springframework.beans.factory.annotation.Autowired
  public CrawlerServiceClient(
      @Value("${crawler.service.url:http://localhost:8000/api/v1/crawl}")
          String crawlerServiceUrl) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    // SSR parsing can take 10-25s for live scraping, set read timeout to 35s
    requestFactory.setReadTimeout(Duration.ofSeconds(35));

    this.restClient =
        RestClient.builder()
            .baseUrl(crawlerServiceUrl)
            .requestFactory(requestFactory)
            .build();

    String monitorUrl = crawlerServiceUrl.replace("/crawl", "/monitor");
    this.monitorRestClient =
        RestClient.builder()
            .baseUrl(monitorUrl)
            .requestFactory(requestFactory)
            .build();

    log.info("Initialized CrawlerServiceClient with endpoints: crawl={}, monitor={}", crawlerServiceUrl, monitorUrl);
  }

  public CrawlerServiceClient(RestClient restClient) {
    this.restClient = restClient;
    this.monitorRestClient = restClient;
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

  /**
   * Cào thử nghiệm 1 KOC thời gian thực (Test Scrape - Luồng DM-05).
   */
  public Optional<Map<String, Object>> testScrape(String profileUrl, String platform) {
    try {
      Map<String, Object> body = Map.of(
          "profile_url", profileUrl != null ? profileUrl.trim() : "",
          "platform", platform != null ? platform.trim().toUpperCase() : "TIKTOK"
      );
      @SuppressWarnings("unchecked")
      Map<String, Object> result = restClient.post()
          .uri("/test-scrape")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(Map.class);
      return Optional.ofNullable(result);
    } catch (Exception e) {
      log.warn("Failed to test scrape {}: {}", profileUrl, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Cào hàng loạt KOC thời gian thực theo ngành hàng hoặc danh sách (Batch Crawl - Luồng DM-05).
   */
  public Optional<Map<String, Object>> batchCrawl(
      String platform, String category, List<String> usernames, int maxChannels) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("platform", platform != null ? platform.trim().toUpperCase() : "TIKTOK");
      if (category != null && !category.isBlank()) {
        body.put("category", category.trim());
      }
      if (usernames != null && !usernames.isEmpty()) {
        body.put("usernames", usernames);
      }
      body.put("max_channels", maxChannels > 0 ? maxChannels : 5);

      @SuppressWarnings("unchecked")
      Map<String, Object> result = restClient.post()
          .uri("/batch-crawl")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(Map.class);
      return Optional.ofNullable(result);
    } catch (Exception e) {
      log.warn("Failed to execute batch crawl (cat={}, users={}): {}", category, usernames, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Kích hoạt cào KOC mới theo danh sách từ khóa do Manager chỉ định.
   */
  public Optional<Map<String, Object>> triggerKeywordDiscovery(List<String> keywords, int maxChannels) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("max_new_kocs", maxChannels > 0 ? maxChannels : 30);
      if (keywords != null && !keywords.isEmpty()) {
        body.put("keywords", keywords);
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> result = restClient.post()
          .uri("/trigger-weekly-discovery")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(Map.class);
      return Optional.ofNullable(result);
    } catch (Exception e) {
      log.warn("Failed to trigger keyword discovery (keywords={}): {}", keywords, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Lấy chỉ số hiệu suất thời gian thực của Crawler (Performance Metrics - Vy Nguyên).
   */
  public Map<String, Object> getPerformanceMetrics() {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> metrics = monitorRestClient.get()
          .uri("/metrics")
          .retrieve()
          .body(Map.class);
      return metrics != null ? metrics : Map.of();
    } catch (Exception e) {
      log.warn("Failed to fetch crawler metrics: {}", e.getMessage());
      return Map.of("error", e.getMessage());
    }
  }

  /**
   * Lấy tổng quan thống kê, tiến trình hoạt động và dữ liệu cào.
   */
  public Map<String, Object> getOverview(int limit) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> overview = monitorRestClient.get()
          .uri(uriBuilder -> uriBuilder.path("/overview").queryParam("limit", limit > 0 ? limit : 20).build())
          .retrieve()
          .body(Map.class);
      return overview != null ? overview : Map.of();
    } catch (Exception e) {
      log.warn("Failed to fetch crawler overview: {}", e.getMessage());
      return Map.of("error", e.getMessage());
    }
  }

  /**
   * Xem chi tiết 1 Creator cụ thể và trạng thái thẩm định staging.
   */
  public Optional<Map<String, Object>> getCreatorDetail(String username, String platform) {
    if (username == null || username.isBlank()) {
      return Optional.empty();
    }
    String cleanUser = username.replace("@", "").trim();
    String plat = (platform != null && !platform.isBlank()) ? platform.trim().toUpperCase() : "TIKTOK";
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> detail = monitorRestClient.get()
          .uri(uriBuilder -> uriBuilder.path("/creator/" + cleanUser).queryParam("platform", plat).build())
          .retrieve()
          .body(Map.class);
      return Optional.ofNullable(detail);
    } catch (Exception e) {
      log.warn("Failed to fetch creator detail for @{} ({}): {}", cleanUser, plat, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Thử lại (Retry) cào KOC bị lỗi hoặc đưa vào lại hàng đợi cào sâu.
   */
  public Optional<Map<String, Object>> retryCrawl(String username, String platform) {
    if (username == null || username.isBlank()) {
      return Optional.empty();
    }
    String cleanUser = username.replace("@", "").trim();
    String plat = (platform != null && !platform.isBlank()) ? platform.trim().toUpperCase() : "TIKTOK";
    try {
      Map<String, Object> body = Map.of(
          "username", cleanUser,
          "platform", plat,
          "force_deep_crawl", true
      );
      @SuppressWarnings("unchecked")
      Map<String, Object> result = restClient.post()
          .uri("/enqueue")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(Map.class);
      return Optional.ofNullable(result);
    } catch (Exception e) {
      log.warn("Failed to retry crawl for @{} ({}): {}", cleanUser, plat, e.getMessage());
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
