package com.influencermatch.backend.creator.dto;

import com.influencermatch.backend.creator.controller.*;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.*;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorIngestionRequest {
  private String platform;
  private String externalId;
  private String userName;
  private String location;
  private String profileUrl;

  // Metrics
  private Long followers;
  private BigDecimal avgViews;
  private BigDecimal avgLikes;
  private BigDecimal avgComments;
  private BigDecimal avgShares;
  private BigDecimal engagementRate;

  // Additional data
  private String niche;
  private String contentSummary;
  private String contact;
  private String category;

  private String freshnessStatus;
  private BigDecimal dataConfidence;
}
