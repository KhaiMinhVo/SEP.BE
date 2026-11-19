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

  @GetMapping("/overview")
  @Operation(summary = "Lấy tổng quan giám sát: Thống kê + Hoạt động đang cào + Dữ liệu đã cào")
  public ApiResponse<Map<String, Object>> getOverview(
      @RequestParam(value = "limit", defaultValue = "20") int limit) {
    Map<String, Object> overview = crawlerServiceClient.getOverview(limit);
    return ApiResponse.ok(overview);
  }

}
