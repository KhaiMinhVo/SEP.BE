package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.client.CrawlerServiceClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/data/crawler", "/admin/crawler"})
@RequiredArgsConstructor
@Tag(name = "Data Manager Crawler Management & Live Demo", description = "Luồng DM-05: Giám sát hiệu suất & Cào thử nghiệm / Hàng loạt KOC cho Data Manager")
public class AdminCrawlerController {

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
    Number maxChannelsNum = (Number) request.getOrDefault("maxChannels", request.getOrDefault("max_channels", 5));
    int maxChannels = maxChannelsNum != null ? maxChannelsNum.intValue() : 5;

    var resultOpt = crawlerServiceClient.batchCrawl(platform, category, usernames, maxChannels);
    return resultOpt
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.badRequest().body(Map.of(
            "status", "error",
            "message", "Thực thi cào hàng loạt thất bại."
        )));
  }

  @GetMapping("/metrics")
  @Operation(summary = "Lấy chỉ số hiệu suất Crawler thời gian thực (Realtime Metrics: Success, Rate Limit 429, Latency)")
  public ApiResponse<Map<String, Object>> getMetrics() {
    Map<String, Object> metrics = crawlerServiceClient.getPerformanceMetrics();
    return ApiResponse.ok(metrics);
  }

  @GetMapping("/overview")
  @Operation(summary = "Lấy tổng quan giám sát: Thống kê + Hoạt động đang cào + Dữ liệu đã cào")
  public ApiResponse<Map<String, Object>> getOverview(
      @RequestParam(value = "limit", defaultValue = "20") int limit) {
    Map<String, Object> overview = crawlerServiceClient.getOverview(limit);
    return ApiResponse.ok(overview);
  }

  @GetMapping("/creator/{username}")
  @Operation(summary = "Xem chi tiết 1 Creator và kết quả thẩm định Staging")
  public ResponseEntity<Map<String, Object>> getCreatorDetail(
      @PathVariable("username") String username,
      @RequestParam(value = "platform", defaultValue = "TIKTOK") String platform) {
    var detail = crawlerServiceClient.getCreatorDetail(username, platform);
    return detail.map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/retry")
  @Operation(summary = "Thử lại (Retry) cào KOC bị lỗi hoặc đưa vào lại hàng đợi")
  public ResponseEntity<Map<String, Object>> retryCrawl(
      @RequestBody Map<String, String> request) {
    String username = request.get("username");
    String platform = request.getOrDefault("platform", "TIKTOK");
    var result = crawlerServiceClient.retryCrawl(username, platform);
    return result.map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.badRequest().body(Map.of(
            "status", "error",
            "message", "Không thể thử lại cào cho: " + username
        )));
  }
}

