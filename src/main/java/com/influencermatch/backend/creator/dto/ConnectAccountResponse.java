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
public class ConnectAccountResponse {
  private UUID accountId;
  private UUID masterId;
  private String platform;
  private String externalId;
  private String userName;
  private String status; // LINKED, CREATED_AND_LINKED, ALREADY_LINKED, CONFLICT_PENDING_REVIEW
  private String message;
}
