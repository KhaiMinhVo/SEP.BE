package com.influencermatch.backend.recommendation.dto;

import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorRecommendationItemDto {
  private UUID creatorId;
  private UUID masterId;
  private String platform;
  private String displayName;
  private String userName;
  private String avatarUrl;
  private Long followers;
  private BigDecimal avgViews;
  private BigDecimal avgLikes;
  private BigDecimal engagementRate;
  private String niche;
  private String category;
  private String creatorType;
  private boolean isClaimed;
  private boolean isInShortlist;
  private ShortlistStatus shortlistStatus;
  private Double finalScore;
  private ScoreBreakdownDto scoreBreakdown;
  private ReasoningCardDto reasoning;
}
