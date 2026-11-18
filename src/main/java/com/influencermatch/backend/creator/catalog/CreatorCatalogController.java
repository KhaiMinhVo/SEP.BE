package com.influencermatch.backend.creator.catalog;

import com.influencermatch.backend.common.dto.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/data")
@RequiredArgsConstructor
@Tag(
    name = "Danh mục định danh nhà sáng tạo",
    description = "Tra cứu định danh chỉ đọc; không tiết lộ thông tin liên hệ hoặc chuyển quyền sở hữu.")
@SecurityRequirement(name = "BearerAuth")
public class CreatorCatalogController {
  final CreatorCatalogService service;

  <T> ResponseEntity<ApiResponse<T>> read(T value) {
    return ResponseEntity.ok()
        .header("Cache-Control", "private, no-store")
        .body(ApiResponse.ok(value));
  }

  @GetMapping("/creator-masters")
  @PreAuthorize("hasAuthority('VIEW_CREATOR_MASTER_CATALOG')")
  @Operation(
      summary = "Danh sách hồ sơ định danh, kể cả hồ sơ chưa liên kết kênh",
      description =
          "Từ khóa q tìm theo tên hoặc mã hồ sơ, username, tên hiển thị hoặc mã định danh nền tảng"
              + " của các kênh đã liên kết.")
  public ResponseEntity<ApiResponse<PageResponse<CatalogDtos.Master>>> masters(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String platform,
      @RequestParam(required = false) String verificationStatus,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return read(service.masters(q, status, platform, verificationStatus, page, size));
  }

  @GetMapping("/creator-masters/summary")
  @PreAuthorize("hasAuthority('VIEW_CREATOR_MASTER_CATALOG')")
  @Operation(summary = "Tổng hợp số lượng hồ sơ, kênh đã liên kết, chưa liên kết và xung đột")
  public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> summary() {
    return read(service.summary());
  }

  @GetMapping("/creator-masters/{id}")
  @PreAuthorize("hasAuthority('VIEW_CREATOR_MASTER_CATALOG')")
  @Operation(summary = "Xem chi tiết hồ sơ định danh")
  public ResponseEntity<ApiResponse<CatalogDtos.Master>> master(@PathVariable UUID id) {
    return read(service.master(id));
  }

  @GetMapping("/creator-masters/{id}/accounts")
  @PreAuthorize("hasAuthority('VIEW_CREATOR_MASTER_CATALOG')")
  @Operation(summary = "Danh sách kênh liên kết với hồ sơ định danh")
  public ResponseEntity<ApiResponse<PageResponse<CatalogDtos.Account>>> accounts(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return read(service.accounts(id, null, null, null, null, page, size));
  }

  @GetMapping("/creator-accounts/unclaimed")
  @PreAuthorize("hasAuthority('VIEW_CREATOR_MASTER_CATALOG')")
  @Operation(summary = "Danh sách kênh chưa liên kết với hồ sơ định danh")
  public ResponseEntity<ApiResponse<PageResponse<CatalogDtos.Account>>> unclaimed(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String platform,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) Boolean isStableId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return read(service.accounts(null, q, platform, sourceType, isStableId, page, size));
  }

  @GetMapping("/creator-identity-events")
  @PreAuthorize("hasAuthority('VIEW_CREATOR_IDENTITY_HISTORY')")
  @Operation(
      summary =
          "Tra cứu lịch sử định danh theo hồ sơ, kênh, người thực hiện hoặc khoảng thời gian",
      description =
          "Chỉ trả về các trường lịch sử được cho phép; mã hồ sơ không xác định trong dữ liệu cũ"
              + " được để trống.")
  public ResponseEntity<ApiResponse<PageResponse<CatalogDtos.Event>>> events(
      @RequestParam(required = false) UUID masterId,
      @RequestParam(required = false) UUID creatorId,
      @RequestParam(required = false) UUID claimId,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return read(
        service.events(masterId, creatorId, claimId, actorId, eventType, from, to, page, size));
  }
}
