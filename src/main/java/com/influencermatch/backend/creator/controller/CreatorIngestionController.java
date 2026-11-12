package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.dto.CreatorIngestionResponse;
import com.influencermatch.backend.creator.service.CreatorIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/creators")
@RequiredArgsConstructor
@Tag(name = "Creators", description = "Brand-facing Creator Search, Listing, and Data Ingestion")
public class CreatorIngestionController {

  private final CreatorIngestionService ingestionService;

  @PostMapping("/ingest")
  @PreAuthorize("hasAuthority('SERVICE_INGEST_CREATOR')")
  @Operation(
      summary = "Ingest creator data",
      description = "Service-only; requires X-Service-Key, not a user JWT",
      security = @SecurityRequirement(name = "CreatorServiceKey"))
  public ResponseEntity<ApiResponse<CreatorIngestionResponse>> ingestCreatorData(
      @RequestBody CreatorIngestionRequest request) {
    CreatorIngestionResponse response = ingestionService.ingestCreatorData(request);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }
}
