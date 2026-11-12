package com.influencermatch.backend.relationship.dto;

import java.util.Collections;
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
public class ContactReadinessResponse {
  private int totalSelected;
  private int readyCount;
  private int manualCount;
  private int unavailableCount;
  private int alreadyContactedCount;
  @Builder.Default
  private List<ContactReadinessItem> items = Collections.emptyList();

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ContactReadinessItem {
    private UUID shortlistItemId;
    private UUID creatorId;
    private UUID masterId;
    private String creatorName;
    private String platform;
    private String avatarUrl;
    private String readinessStatus; // READY, MANUAL_CONTACT, UNAVAILABLE, ALREADY_CONTACTED
    private String contactChannel;  // IN_APP_PORTAL, SYSTEM_EMAIL_OUTBOX, MANUAL_SOCIAL_LINK, NONE, EXISTING_INVITATION
    private String contactTarget;   // email, profileUrl, internal portal
    private String action;          // Action description
    private boolean canProceed;     // true if ready to proceed
    private String reason;          // Explanatory reason
  }
}
