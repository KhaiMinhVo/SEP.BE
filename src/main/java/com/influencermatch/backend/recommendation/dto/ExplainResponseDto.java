package com.influencermatch.backend.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExplainResponseDto {
  private String status;
  private String summary;
  @Builder.Default
  private List<String> reasons = Collections.emptyList();
  @JsonProperty("score_breakdown")
  private Map<String, Object> scoreBreakdown;
  @Builder.Default
  private boolean explanationAvailable = true;
}
