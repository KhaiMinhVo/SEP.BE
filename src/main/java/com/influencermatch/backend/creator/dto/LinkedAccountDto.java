package com.influencermatch.backend.creator.dto;

import com.influencermatch.backend.creator.enums.CreatorSourceType;
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
public class LinkedAccountDto {
  private UUID id;
  private String platform;
  private String externalId;
  private String userName;
  private String displayName;
  private String avatarUrl;
  private String profileUrl;
  private CreatorSourceType sourceType;
  private LocalDateTime authorizedAt;
}
