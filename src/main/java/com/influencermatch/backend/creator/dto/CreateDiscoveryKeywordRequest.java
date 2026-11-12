package com.influencermatch.backend.creator.dto;

import com.influencermatch.backend.creator.enums.KeywordType;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateDiscoveryKeywordRequest {
  @NotBlank(message = "Keyword cannot be blank")
  private String keyword;

  private String category;
  private KeywordType keywordType;
  private String platform;
  private Boolean isActive;
}

