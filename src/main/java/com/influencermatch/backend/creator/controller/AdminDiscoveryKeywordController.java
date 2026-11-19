package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.dto.*;
import com.influencermatch.backend.creator.enums.KeywordType;
import com.influencermatch.backend.creator.service.DiscoveryKeywordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/data/crawler/keywords")
@org.springframework.security.access.prepost.PreAuthorize("hasRole('DATA_MANAGER')")
@RequiredArgsConstructor
@Tag(name = "Data Manager Keyword Discovery Management", description = "Quản lý từ khóa săn KOC mục tiêu (Target) và từ khóa rác (Blacklist)")
public class AdminDiscoveryKeywordController {

  private final DiscoveryKeywordService keywordService;

  @GetMapping
  @Operation(summary = "Lấy danh sách từ khóa săn lùng / chặn rác")
  public ApiResponse<List<DiscoveryKeywordDto>> listKeywords(
      @RequestParam(required = false) KeywordType type,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) Boolean isActive,
      @RequestParam(required = false) String q) {
    return ApiResponse.ok(keywordService.listKeywords(type, category, isActive, q));
  }

  @PostMapping
  @Operation(summary = "Thêm từ khóa mới (Target hoặc Blacklist)")
  public ApiResponse<DiscoveryKeywordDto> createKeyword(
      @Valid @RequestBody CreateDiscoveryKeywordRequest request) {
    return ApiResponse.ok("Tạo từ khóa thành công", keywordService.createKeyword(request));
  }

  @PutMapping("/{id}")
  @Operation(summary = "Cập nhật từ khóa")
  public ApiResponse<DiscoveryKeywordDto> updateKeyword(
      @PathVariable UUID id,
      @Valid @RequestBody UpdateDiscoveryKeywordRequest request) {
    return ApiResponse.ok("Cập nhật từ khóa thành công", keywordService.updateKeyword(id, request));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Xóa từ khóa")
  public ApiResponse<Void> deleteKeyword(@PathVariable UUID id) {
    keywordService.deleteKeyword(id);
    return ApiResponse.empty();
  }

  @PostMapping("/{id}/trigger-crawl")
  @Operation(summary = "Kích hoạt cào ngay lập tức theo từ khóa này (Live Crawl Trigger)")
  public ResponseEntity<Map<String, Object>> triggerCrawl(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "30") Integer maxChannels) {
    return ResponseEntity.ok(keywordService.triggerCrawlForKeyword(id, maxChannels));
  }
}

