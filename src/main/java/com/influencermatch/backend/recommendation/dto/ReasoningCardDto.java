package com.influencermatch.backend.recommendation.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReasoningCardDto {
  private String summary;
  private List<String> reasons;
  private boolean explanationAvailable;
}
