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
public class OAuthCallbackResponse {
  private UUID masterId;
  private UUID creatorId;
  private String canonicalName;
  private String status;
}
