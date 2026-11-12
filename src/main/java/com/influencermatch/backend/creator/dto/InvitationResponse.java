package com.influencermatch.backend.creator.dto;

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
public class InvitationResponse {
  private UUID id;
  private UUID creatorId;
  private String email;
  private String status;
  private String emailStatus;
  private LocalDateTime expiresAt;
}
