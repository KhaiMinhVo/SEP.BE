package com.influencermatch.backend.creator.dto;

import com.influencermatch.backend.creator.enums.KeywordType;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscoveryKeywordDto {
  private UUID id;
  private String keyword;
  private String category;
  private KeywordType keywordType;
  private String platform;
  private Boolean isActive;
  private Long creatorCount;
  private LocalDateTime lastCrawledAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}

