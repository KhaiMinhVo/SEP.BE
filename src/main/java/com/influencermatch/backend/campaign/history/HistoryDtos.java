package com.influencermatch.backend.campaign.history;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.campaign.dto.*;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class HistoryDtos {
  private HistoryDtos() {}

  public enum SourceKind {
    CONTEXT_SNAPSHOT,
    CURRENT_DRAFT,
    CURRENT_RECORD_FALLBACK
  }

  public record CampaignItem(
      UUID id,
      String name,
      CampaignStatus status,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      List<String> platforms,
      long collaborationCount,
      long completedCount) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Context(
      UUID id,
      Integer version,
      UUID brandContextId,
      boolean active,
      LocalDateTime createdAt,
      String snapshotKind,
      JsonNode brief,
      List<String> missingFields) {}

  public record Coverage(
      int requiredCollaborationCount, int includedCollaborationCount, int distinctPostCount) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Metric(
      BigDecimal value,
      String availability,
      List<String> unavailableReasons,
      String unit,
      Coverage coverage) {}

  public record Report(
      String policyVersion,
      String financialFormulaVersion,
      String basis,
      String metricSource,
      int completedCount,
      int unfinishedCount,
      int cancelledCount,
      int eligibleCount,
      List<UUID> missingSourceCollaborationIds,
      Map<String, Metric> metrics) {}

  public record Detail(
      CampaignResponse currentCampaign,
      String currentProfileSource,
      Context latestContext,
      String snapshotAvailability,
      Map<String, Long> dealCounts,
      Report report,
      CopyOrigin copyOrigin) {}

  public record CollaborationItem(
      UUID id,
      UUID dealId,
      CollaborationStatus status,
      UUID originalMasterId,
      BigDecimal agreedFee,
      String currency,
      String executionSource,
      LocalDateTime startAt,
      LocalDateTime completedAt,
      LocalDateTime updatedAt,
      Long acceptedOutcomeRevision,
      String provenance,
      String currentProfileSource,
      List<Channel> currentChannels) {}

  public record Channel(
      UUID creatorId, UUID currentMasterId, String platform, String username, String displayName) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Agreement(
      UUID proposalId,
      Long revision,
      String availability,
      String source,
      BigDecimal fee,
      String currency,
      String terms,
      JsonNode deliverables,
      JsonNode consentReference) {}

  public record DealEvent(
      UUID id, String action, long revision, UUID actorId, Instant occurredAt, JsonNode metadata) {}

  public record CollaborationDetail(
      CollaborationItem collaboration,
      Agreement agreedProposal,
      List<DealEvent> dealHistory,
      JsonNode execution,
      JsonNode legacyOutcome,
      String currentProfileSource) {}

  public record CopyOrigin(
      UUID sourceCampaignId, UUID sourceContextId, long sourceRevision, SourceKind sourceKind) {}

  public record Preview(
      CopyOrigin source,
      JsonNode draft,
      List<String> resetFields,
      List<String> reviewFields,
      List<String> warnings) {}

  public record Duplicate(
      @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
              using = com.influencermatch.backend.collaboration.dto.StrictNumbers.LongValue.class)
          @NotNull
          @PositiveOrZero
          Long expectedSourceRevision,
      @NotNull SourceKind sourceKind,
      UUID sourceContextId,
      @NotNull @Valid CampaignRequest draft) {}

  public record DuplicateResult(CampaignResponse campaign, CopyOrigin source) {}
}
