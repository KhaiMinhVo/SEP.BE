package com.influencermatch.backend.relationship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
public class ShortlistOutreachResponse {

  @Schema(description = "Campaign ID")
  private UUID campaignId;

  @Schema(description = "Total shortlist items requested for outreach")
  private int totalRequested;

  @Schema(description = "Count of creators successfully processed into CONTACTED CRM stage")
  private int contactedCount;

  @Schema(description = "IDs of created or updated BrandCreatorRelationship records")
  private List<UUID> relationshipIds;

  @Schema(description = "IDs of claim invitations dispatched or found pending")
  private List<UUID> dispatchedInvitationIds;

  @Schema(description = "Detailed outcome per shortlist candidate")
  private List<OutreachItemResult> items;

  @Schema(description = "Summary message")
  private String message;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class OutreachItemResult {
    @Schema(description = "Shortlist item ID")
    private UUID shortlistItemId;

    @Schema(description = "Target Creator ID")
    private UUID creatorId;

    @Schema(description = "Creator Display Name / Username")
    private String creatorName;

    @Schema(description = "Created or updated BrandCreatorRelationship ID")
    private UUID relationshipId;

    @Schema(description = "Dispatched or existing CreatorInvitation ID if applicable")
    private UUID invitationId;

    @Schema(description = "Contact delivery status")
    private String contactStatus;

    @Schema(description = "Details / notes on contact status")
    private String message;
  }
}
