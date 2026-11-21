package com.influencermatch.backend.campaign.controller;

import com.influencermatch.backend.brand.service.BrandService;
import com.influencermatch.backend.campaign.service.CampaignImageStorage;
import com.influencermatch.backend.common.dto.ApiResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class CampaignImageController {
  private final BrandService brands;
  private final CampaignImageStorage storage;

  @PostMapping(value = "/brands/{brandId}/campaign-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('CREATE_CAMPAIGN') or hasAuthority('UPDATE_CAMPAIGN')")
  public ApiResponse<CampaignImageStorage.Image> upload(
      @PathVariable UUID brandId, @RequestPart("file") MultipartFile file, Authentication auth) {
    brands.requireAccessible(brandId, auth);
    return ApiResponse.ok(storage.upload(brandId, file));
  }
}
