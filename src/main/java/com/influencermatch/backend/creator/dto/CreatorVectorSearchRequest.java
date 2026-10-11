package com.influencermatch.backend.creator.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorVectorSearchRequest {
  private List<Float> vector;
  private int limit;
}
