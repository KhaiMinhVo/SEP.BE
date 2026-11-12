package com.influencermatch.backend.creator.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyTokenResponse {
  private UUID creatorId;
  private String platform;
  private String userName;
  private String displayName;
  private String avatarUrl;
}
