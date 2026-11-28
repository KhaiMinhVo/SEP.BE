package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.service.CreatorRefreshService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/campaigns/{campaignId}/creators/{creatorId}/refresh")
public class CreatorRefreshController {
  private final CreatorRefreshService refresh;
  @PostMapping @PreAuthorize("hasAuthority('REQUEST_CREATOR_REFRESH')")
  public ResponseEntity<ApiResponse<CreatorRefreshService.Response>> request(
      @PathVariable UUID campaignId, @PathVariable UUID creatorId,
      @RequestHeader("Idempotency-Key") UUID key) {
    return ResponseEntity.accepted().body(ApiResponse.ok(refresh.request(campaignId, creatorId, key)));
  }
}
