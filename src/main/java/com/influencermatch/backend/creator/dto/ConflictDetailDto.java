package com.influencermatch.backend.creator.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConflictDetailDto {
  private UUID id;
  private String conflictType;
  private UUID sourceAccountId;
  private String sourceAccountPlatform;
  private String sourceAccountUsername;
  private String sourceAccountExternalId;
  private UUID currentMasterId;
  private UUID targetMasterId;
  private String targetMasterName;
  private Map<String, Object> conflictDetails;
  private String status;
  private String resolutionNotes;
  private UUID resolvedByUserId;
  private LocalDateTime resolvedAt;
  private LocalDateTime createdAt;
}
