package com.influencermatch.backend.recommendation.dto;

import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExcludedCandidateDto {
  private UUID creatorId;
  private UUID masterId;
  private String platform;
  private String displayName;
  private String userName;
  private String avatarUrl;
  private String exclusionType; // INELIGIBLE, NOT_SCORABLE, INVALID_DATA
  private List<String> reasons;
  private String message;
}
