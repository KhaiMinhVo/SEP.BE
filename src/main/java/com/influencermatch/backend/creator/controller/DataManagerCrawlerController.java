package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.client.CrawlerServiceClient;
import com.influencermatch.backend.creator.dto.PostMetricsDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/data/crawler")
@org.springframework.security.access.prepost.PreAuthorize("hasRole('DATA_MANAGER')")
@RequiredArgsConstructor
@Tag(name = "Data Manager Crawler", description = "Luồng DM-05: Giám sát hiệu suất & Cào thử nghiệm / Hàng loạt KOC cho Data Manager")
public class DataManagerCrawlerController {

  private final CrawlerServiceClient crawlerServiceClient;

  @PostMapping("/db-normalization/preview")
  @Operation(summary = "Preview existing database normalization without recrawling or changing observations")
  public ResponseEntity<Map<String, Object>> previewDatabaseNormalization(@RequestBody Map<String, Object> request) {
    return normalizationResponse(crawlerServiceClient.previewDatabaseNormalization(request));
  }

  @PostMapping("/db-normalization/apply")
  @Operation(summary = "Apply a saved normalization plan; stale snapshots conflict and each change is audited")
  public ResponseEntity<Map<String, Object>> applyDatabaseNormalization(@RequestBody Map<String, String> request) {
    String planId = request.getOrDefault("planId", request.get("plan_id"));
    if (planId == null || !planId.matches("normalize-[0-9a-f]{32}"))
      return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Invalid normalization plan ID"));
    return normalizationResponse(crawlerServiceClient.applyDatabaseNormalization(planId,
        com.influencermatch.backend.security.permission.Permissions.actor().getId()));
  }

  @PostMapping("/db-normalization/reclassify")
  @Operation(summary = "Recheck stored captions for category proposals with verbatim evidence; never reingest stored data")
  public ResponseEntity<Map<String, Object>> reclassifyStoredDatabaseContent(@RequestBody Map<String, Object> request) {
    return normalizationResponse(crawlerServiceClient.reclassifyStoredDatabaseContent(request));
  }

  @GetMapping("/db-normalization/content-report")
  public ResponseEntity<Map<String, Object>> latestDatabaseContentAudit() {
    return normalizationResponse(crawlerServiceClient.latestDatabaseContentAudit());
  }

  @GetMapping("/db-normalization/reports/{planId}")
  public ResponseEntity<Map<String, Object>> databaseNormalizationReport(@PathVariable String planId) {
    if (!planId.matches("normalize-[0-9a-f]{32}"))
      return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Invalid normalization plan ID"));
    return normalizationResponse(crawlerServiceClient.databaseNormalizationReport(planId));
  }

  private ResponseEntity<Map<String, Object>> normalizationResponse(java.util.Optional<Map<String, Object>> response) {
    return response.map(body -> {
      int status = body.get("upstreamStatus") instanceof Number code ? code.intValue() : 200;
      return ResponseEntity.status(status >= 400 && status <= 599 ? status : 200).body(body);
    }).orElseGet(() -> ResponseEntity.status(503).body(Map.of(
        "status", "error", "message", "Database normalization unavailable; check crawler database and service-key configuration")));
  }

  @PostMapping("/test-scrape")
  @Operation(summary = "Cào thử nghiệm 1 KOC thời gian thực (Test Scrape)")
  public ResponseEntity<Map<String, Object>> testScrape(
      @RequestBody Map<String, String> request) {
    String profileUrl = request.getOrDefault("profileUrl", request.get("profile_url"));
    String platform = request.getOrDefault("platform", "TIKTOK");

    var resultOpt = crawlerServiceClient.testScrape(profileUrl, platform);
    return resultOpt
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.badRequest().body(Map.of(
            "status", "error",
            "message", "Không thể cào dữ liệu từ kênh: " + profileUrl
        )));
  }

  @PostMapping("/batch-crawl")
  @Operation(summary = "Kích hoạt cào hàng loạt KOC theo ngành nghề hoặc danh sách (Batch Crawl)")
  public ResponseEntity<Map<String, Object>> batchCrawl(
      @RequestBody Map<String, Object> request) {
    String platform = (String) request.getOrDefault("platform", "TIKTOK");
    String category = (String) request.get("category");
    @SuppressWarnings("unchecked")
    List<String> usernames = (List<String>) request.get("usernames");
    @SuppressWarnings("unchecked")
    List<String> keywords = (List<String>) request.get("keywords");
    Number maxChannelsNum = (Number) request.getOrDefault("maxChannels", request.getOrDefault("max_channels", 5));
    int maxChannels = maxChannelsNum != null ? maxChannelsNum.intValue() : 5;

    var resultOpt = crawlerServiceClient.batchCrawl(platform, category, keywords, usernames, maxChannels);
    return resultOpt
        .map(result -> {
          var response = new HashMap<>(result);
          if (result.get("task_id") != null) {
            response.put("status_url", "/api/v1/data/crawler/batch-crawl/jobs/" + result.get("task_id"));
          }
          return ResponseEntity.accepted().body((Map<String, Object>) response);
        })
        .orElseGet(() -> ResponseEntity.status(503).body(Map.of(
            "status", "error",
            "message", "Crawler could not accept the batch request."
        )));
  }

  @GetMapping("/batch-crawl/jobs/{taskId}")
  public ResponseEntity<Map<String, Object>> getBatchCrawlJob(
      @PathVariable java.util.UUID taskId) {
    return crawlerServiceClient.getBatchCrawlJob(taskId)
        .map(result -> {
          var response = new HashMap<>(result);
          response.put("status_url", "/api/v1/data/crawler/batch-crawl/jobs/" + taskId);
          return ResponseEntity.ok((Map<String, Object>) response);
        })
        .orElseGet(() -> ResponseEntity.status(503).body(Map.of(
            "status", "error", "message", "Crawler job status is unavailable."
        )));
  }

  @PostMapping("/post-metrics")
  @Operation(summary = "Bóc tách chỉ số thời gian thực của bài đăng (WF-03 Post Metrics)")
  public ResponseEntity<Map<String, Object>> getPostMetrics(
      @RequestBody Map<String, Object> request) {
    String postUrl = (String) request.getOrDefault("postUrl", request.get("post_url"));
    if (postUrl == null || postUrl.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of(
          "status", "error",
          "message", "post_url hoặc postUrl không được để trống"
      ));
    }

    var metricsOpt = crawlerServiceClient.fetchPostMetrics(postUrl);
    return metricsOpt
        .map(metrics -> ResponseEntity.ok(Map.<String, Object>of(
            "status", "success",
            "crawlerResponse", metrics
        )))
        .orElseGet(() -> ResponseEntity.badRequest().body(Map.of(
            "status", "error",
            "message", "Không thể lấy số liệu bài đăng từ URL: " + postUrl
        )));
  }

  @GetMapping("/overview")
  @Operation(summary = "Lấy tổng quan giám sát: Thống kê + Hoạt động đang cào + Dữ liệu đã cào")
  public ApiResponse<Map<String, Object>> getOverview(
      @RequestParam(value = "limit", defaultValue = "20") int limit) {
    Map<String, Object> overview = crawlerServiceClient.getOverview(limit);
    return ApiResponse.ok(overview);
  }

  @GetMapping("/trends")
  @Operation(summary = "Lấy xu hướng thu thập dữ liệu theo ngày (7 hoặc 30 ngày)")
  public ApiResponse<Map<String, Object>> getTrends(
      @RequestParam(value = "days", defaultValue = "7") int days) {
    List<Map<String, Object>> items = crawlerServiceClient.getTrends(days);
    return ApiResponse.ok(Map.of(
        "items", items,
        "source", "Crawler / PostgreSQL (public_creator_metric)",
        "isFallback", false));
  }

}
