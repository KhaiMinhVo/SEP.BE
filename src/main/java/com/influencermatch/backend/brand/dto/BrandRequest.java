package com.influencermatch.backend.brand.dto;

import com.influencermatch.backend.brand.controller.*;
import com.influencermatch.backend.brand.enums.*;
import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.repository.*;
import com.influencermatch.backend.brand.service.*;
import jakarta.validation.constraints.*;
import java.util.*;
import org.hibernate.validator.constraints.URL;

public record BrandRequest(
    UUID userId,
    @NotBlank @Size(max = 200) String businessName,
    @Size(max = 120) String industry,
    @Size(max = 20) List<@NotBlank @Size(max = 100) String> productCategories,
    @Size(max = 500) @URL String website,
    @Size(max = 150) String location,
    @Size(max = 20) List<@NotBlank @Size(max = 100) String> targetMarkets,
    @Size(max = 20) List<@NotBlank @Size(max = 150) String> targetAudiences,
    @Size(max = 150) String brandTone,
    @Size(max = 3) List<Platform> preferredPlatforms,
    @Size(max = 1000) String description,
    @Size(max = 30) String phoneNumber,
    @Size(max = 1000) String positioning,
    @Size(max = 4000) String guidelines) {
  public BrandRequest(
      UUID userId,
      String businessName,
      String industry,
      List<String> productCategories,
      String website,
      String location,
      List<String> targetMarkets,
      List<String> targetAudiences,
      String brandTone,
      List<Platform> preferredPlatforms,
      String description,
      String phoneNumber) {
    this(
        userId,
        businessName,
        industry,
        productCategories,
        website,
        location,
        targetMarkets,
        targetAudiences,
        brandTone,
        preferredPlatforms,
        description,
        phoneNumber,
        null,
        null);
  }

  public BrandRequest(
      UUID userId,
      String businessName,
      String industry,
      List<String> productCategories,
      String website,
      String location,
      List<String> targetMarkets,
      List<String> targetAudiences,
      String brandTone,
      List<Platform> preferredPlatforms,
      String description) {
    this(
        userId,
        businessName,
        industry,
        productCategories,
        website,
        location,
        targetMarkets,
        targetAudiences,
        brandTone,
        preferredPlatforms,
        description,
        null,
        null,
        null);
  }
}
