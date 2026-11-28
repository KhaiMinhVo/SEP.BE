package com.influencermatch.backend.creator.client;

import com.influencermatch.backend.creator.dto.CrawlerResolveAuthorResponse;
import com.influencermatch.backend.creator.dto.PostMetricsDto;
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
  private final RestClient qualityRestClient;

  @Value("${DB_NORMALIZATION_API_KEY:${CREATOR_INGESTION_API_KEY:}}")
  private String normalizationApiKey;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    var qualityFactory = new SimpleClientHttpRequestFactory();
    qualityFactory.setConnectTimeout(Duration.ofSeconds(5));
    qualityFactory.setReadTimeout(Duration.ofSeconds(120));
    this.qualityRestClient = RestClient.builder()
        .baseUrl(crawlerServiceUrl.replaceFirst("/crawl/?$", "/quality"))
        .requestFactory(qualityFactory).build();

    log.info("Initialized CrawlerServiceClient with endpoints: crawl={}, monitor={}", crawlerServiceUrl, monitorUrl);
  }

  public CrawlerServiceClient(RestClient restClient) {
    this.restClient = restClient;
    this.monitorRestClient = restClient;
    this.qualityRestClient = restClient;
  }

  public Optional<Map<String, Object>> previewDatabaseNormalization(Map<String, Object> request) {
    return normalizationPost("/db-normalization/preview", request);
  }

  public Optional<Map<String, Object>> applyDatabaseNormalization(String planId, UUID actorId) {
    return normalizationPost("/db-normalization/apply", Map.of("plan_id", planId, "actor_id", actorId.toString()));
  }

  public Optional<Map<String, Object>> reclassifyStoredDatabaseContent(Map<String, Object> request) {
    var body = new HashMap<>(request);
    body.put("mode", "RECLASSIFY");
    body.put("dry_run", true);
    body.putIfAbsent("limit", 5);
    return normalizationPost("/batch-reclassify", body);
  }

  @SuppressWarnings("unchecked")
  public Optional<Map<String, Object>> latestDatabaseContentAudit() {
    if (normalizationApiKey == null || normalizationApiKey.isBlank()) return Optional.empty();
    try {
      return Optional.ofNullable(qualityRestClient.get().uri("/audit-report")
          .header("X-Service-Key", normalizationApiKey).retrieve().body(Map.class));
    } catch (org.springframework.web.client.RestClientResponseException failure) {
      return Optional.of(Map.of("status", "error", "upstreamStatus", failure.getStatusCode().value(),
          "message", "Content audit report unavailable"));
    } catch (Exception failure) {
      return Optional.empty();
    }
  }

  @SuppressWarnings("unchecked")
  private Optional<Map<String, Object>> normalizationPost(String path, Map<String, Object> request) {
    if (normalizationApiKey == null || normalizationApiKey.isBlank()) return Optional.empty();
    try {
      return Optional.ofNullable(qualityRestClient.post().uri(path)
          .header("X-Service-Key", normalizationApiKey).contentType(MediaType.APPLICATION_JSON)
          .body(request).retrieve().body(Map.class));
    } catch (org.springframework.web.client.RestClientResponseException failure) {
      return Optional.of(Map.of("status", "error", "upstreamStatus", failure.getStatusCode().value(),
          "message", "Normalization request rejected; preview the plan again or check service configuration"));
    } catch (Exception failure) {
      log.warn("Database normalization request failed: {}", failure.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  @SuppressWarnings("unchecked")
  public Optional<Map<String, Object>> databaseNormalizationReport(String planId) {
    if (normalizationApiKey == null || normalizationApiKey.isBlank()) return Optional.empty();
    try {
      return Optional.ofNullable(qualityRestClient.get().uri("/db-normalization/reports/{planId}", planId)
          .header("X-Service-Key", normalizationApiKey).retrieve().body(Map.class));
    } catch (org.springframework.web.client.RestClientResponseException failure) {
      return Optional.of(Map.of("status", "error", "upstreamStatus", failure.getStatusCode().value(),
          "message", "Normalization report unavailable"));
    } catch (Exception failure) {
      log.warn("Database normalization report unavailable: {}", failure.getClass().getSimpleName());
      return Optional.empty();
    }
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
    return batchCrawl(platform, category, null, usernames, maxChannels);
  }

  public Optional<Map<String, Object>> batchCrawl(
      String platform, String category, List<String> keywords, List<String> usernames, int maxChannels) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("platform", platform != null ? platform.trim().toUpperCase() : "TIKTOK");
      if (category != null && !category.isBlank()) {
        body.put("category", category.trim());
      }
      if (keywords != null && !keywords.isEmpty()) {
        body.put("keywords", keywords);
      }
      if (usernames != null && !usernames.isEmpty()) {
        body.put("usernames", usernames);
      }
      body.put("max_channels", maxChannels > 0 ? maxChannels : 5);

      @SuppressWarnings("unchecked")
      Map<String, Object> result = restClient.post()
          .uri("/batch-crawl/jobs")
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

  public Optional<Map<String, Object>> getBatchCrawlJob(UUID taskId) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> result = restClient.get()
          .uri("/batch-crawl/jobs/{taskId}", taskId)
          .retrieve()
          .body(Map.class);
      return Optional.ofNullable(result);
    } catch (Exception e) {
      log.warn("Failed to fetch batch crawl job {}: {}", taskId, e.getMessage());
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

  /**
   * Biểu đồ chuỗi thời gian cào theo ngày (7 ngày / 30 ngày) cho DM-05 Trends.
   */
  public List<Map<String, Object>> getTrends(int days) {
    int safeDays = (days > 0 && days <= 90) ? days : 7;
    try {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> remoteTrends = monitorRestClient.get()
          .uri(uriBuilder -> uriBuilder.path("/trends").queryParam("days", safeDays).build())
          .retrieve()
          .body(List.class);
      if (remoteTrends != null && !remoteTrends.isEmpty()) {
        return remoteTrends;
      }
    } catch (Exception e) {
      log.debug("Remote crawler monitor /trends not available: {}", e.getMessage());
    }

    if (jdbc == null) {
      return List.of();
    }
    try {
      String sql = """
          SELECT 
              TO_CHAR(d.day, 'YYYY-MM-DD') AS date_str,
              COALESCE(COUNT(m.id), 0) AS ingested_count,
              COALESCE(COUNT(m.id) FILTER (WHERE m.data_confidence >= 0.7), 0) AS success_count,
              COALESCE(COUNT(m.id) FILTER (WHERE m.data_confidence < 0.7 OR m.freshness_status = 'REFRESH_FAILED'), 0) AS failed_count
          FROM generate_series(
              CURRENT_DATE - (INTERVAL '1 day' * (? - 1)),
              CURRENT_DATE,
              INTERVAL '1 day'
          ) d(day)
          LEFT JOIN public_creator_metric m ON DATE(m.collected_at) = DATE(d.day)
          GROUP BY d.day
          ORDER BY d.day ASC
          """;
      return jdbc.query(sql, (rs, rowNum) -> {
        Map<String, Object> map = new HashMap<>();
        String d = rs.getString("date_str");
        long ingested = rs.getLong("ingested_count");
        long success = rs.getLong("success_count");
        long failed = rs.getLong("failed_count");
        map.put("date", d);
        map.put("ingestedCount", ingested);
        map.put("ingested_count", ingested);
        map.put("successCount", success);
        map.put("success_count", success);
        map.put("failedCount", failed);
        map.put("failed_count", failed);
        return map;
      }, safeDays);
    } catch (Exception e) {
      log.warn("Failed to query database crawler trends: {}", e.getMessage());
      return List.of();
    }
  }

  /** OAuth resolution must be checked against an already-known stable channel ID. */
  public CrawlerResolveAuthorResponse resolveTikTokIdentity(
      String username, String expectedExternalId) {
    return resolveTikTokAuthor(username, expectedExternalId)
        .orElseThrow(
            () ->
                new com.influencermatch.backend.exception.BusinessException(
                    com.influencermatch.backend.exception.ErrorCode.TIKTOK_IDENTITY_RESOLUTION_FAILED,
                    "Unable to verify TikTok identity; use manual channel resolution"));
  }

  /**
   * Bóc tách chỉ số thời gian thực của bài đăng qua Python Crawler (WF-03 Post Metrics).
   * Gọi tới endpoint POST /api/v1/crawl/post-metrics.
   */
  public Optional<PostMetricsDto.PostMetricsResponse> fetchPostMetrics(String postUrl) {
    if (postUrl == null || postUrl.isBlank()) {
      return Optional.empty();
    }
    String cleanUrl = postUrl.trim();
    try {
      PostMetricsDto.PostMetricsResponse response =
          restClient
              .post()
              .uri("/post-metrics")
              .contentType(MediaType.APPLICATION_JSON)
              .body(Map.of("post_url", cleanUrl))
              .retrieve()
              .body(PostMetricsDto.PostMetricsResponse.class);
      return Optional.ofNullable(response);
    } catch (Exception e) {
      log.warn("Failed to fetch post metrics from crawler for {}: {}", cleanUrl, e.getMessage());
      return Optional.empty();
    }
  }
}
