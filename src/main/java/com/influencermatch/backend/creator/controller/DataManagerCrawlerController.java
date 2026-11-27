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
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.badRequest().body(Map.of(
            "status", "error",
            "message", "Thực thi cào hàng loạt thất bại."
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
