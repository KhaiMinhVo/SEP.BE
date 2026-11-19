package com.influencermatch.backend.campaign.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.campaign.repository.HistoricalCampaignRepository;
import com.influencermatch.backend.collaboration.model.CampaignOutcome;
import com.influencermatch.backend.collaboration.model.Collaboration;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorMaster;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 1: Append-Only Historical Evidence Service.
 * Manages revisioned, immutable historical evidence records.
 * AMEND and REVOKE create new revisions instead of updating/deleting existing rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalEvidenceService {

  private final HistoricalCampaignRepository historyRepository;

  /**
   * Creates the initial revision (revision 1) of an evidence item.
   */
  @Transactional
  public HistoricalCampaign createEvidence(
      Creator creator,
      CreatorMaster master,
      Collaboration collaboration,
      CampaignOutcome outcome,
      String evidenceType,
      JsonNode evidenceValue,
      String textValue,
      String quality,
      UUID sourceReviewId,
      Long sourceRevision,
      String provenance,
      LocalDateTime businessOccurredAt) {

    UUID rootId = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.now();

    HistoricalCampaign entry = HistoricalCampaign.builder()
        .id(rootId)
        .evidenceRootId(rootId)
        .revisionNumber(1L)
        .schemaVersion("1.0")
        .creator(creator)
        .master(master)
        .collaboration(collaboration)
        .outcome(outcome)
        .evidenceType(evidenceType != null ? evidenceType : "COLLABORATION_REVIEW")
        .evidenceValue(evidenceValue)
        .textValue(textValue)
        .quality(quality != null ? quality : "SELF_REPORTED")
        .sourceReviewId(sourceReviewId)
        .sourceRevision(sourceRevision != null ? sourceRevision : 1L)
        .provenance(provenance != null ? provenance : "BRAND_ACCEPTED")
        .status("ACTIVE")
        .businessOccurredAt(businessOccurredAt != null ? businessOccurredAt : now)
        .recordedAt(now)
        .build();

    return historyRepository.save(entry);
  }

  /**
   * Amends an existing evidence root by appending a new revision.
   */
  @Transactional
  public HistoricalCampaign amendEvidence(
      UUID evidenceRootId,
      JsonNode newEvidenceValue,
      String newTextValue,
      String newQuality,
      LocalDateTime businessOccurredAt) {

    List<HistoricalCampaign> revisions =
        historyRepository.findByEvidenceRootIdOrderByRevisionNumberDesc(evidenceRootId);
    if (revisions.isEmpty()) {
      throw new IllegalArgumentException("Evidence root not found: " + evidenceRootId);
    }

    HistoricalCampaign latest = revisions.get(0);
    if ("REVOKED".equals(latest.getStatus())) {
      throw new IllegalStateException("Cannot amend revoked evidence: " + evidenceRootId);
    }

    long nextRev = latest.getRevisionNumber() + 1;
    LocalDateTime now = LocalDateTime.now();

    HistoricalCampaign amended = HistoricalCampaign.builder()
        .id(UUID.randomUUID())
        .evidenceRootId(evidenceRootId)
        .revisionNumber(nextRev)
        .schemaVersion(latest.getSchemaVersion())
        .supersedesId(latest.getId())
        .creator(latest.getCreator())
        .master(latest.getMaster())
        .collaboration(latest.getCollaboration())
        .outcome(latest.getOutcome())
        .evidenceType(latest.getEvidenceType())
        .evidenceValue(newEvidenceValue != null ? newEvidenceValue : latest.getEvidenceValue())
        .textValue(newTextValue != null ? newTextValue : latest.getTextValue())
        .quality(newQuality != null ? newQuality : latest.getQuality())
        .sourceReviewId(latest.getSourceReviewId())
        .sourceRevision(latest.getSourceRevision())
        .provenance(latest.getProvenance())
        .status("ACTIVE")
        .businessOccurredAt(businessOccurredAt != null ? businessOccurredAt : latest.getBusinessOccurredAt())
        .recordedAt(now)
        .build();

    return historyRepository.save(amended);
  }

  /**
   * Revokes an evidence root by appending a terminal REVOKED revision.
   */
  @Transactional
  public HistoricalCampaign revokeEvidence(UUID evidenceRootId, String reason) {
    List<HistoricalCampaign> revisions =
        historyRepository.findByEvidenceRootIdOrderByRevisionNumberDesc(evidenceRootId);
    if (revisions.isEmpty()) {
      throw new IllegalArgumentException("Evidence root not found: " + evidenceRootId);
    }

    HistoricalCampaign latest = revisions.get(0);
    if ("REVOKED".equals(latest.getStatus())) {
      return latest; // Already revoked, idempotent
    }

    long nextRev = latest.getRevisionNumber() + 1;
    LocalDateTime now = LocalDateTime.now();

    HistoricalCampaign revoked = HistoricalCampaign.builder()
        .id(UUID.randomUUID())
        .evidenceRootId(evidenceRootId)
        .revisionNumber(nextRev)
        .schemaVersion(latest.getSchemaVersion())
        .supersedesId(latest.getId())
        .creator(latest.getCreator())
        .master(latest.getMaster())
        .collaboration(latest.getCollaboration())
        .outcome(latest.getOutcome())
        .evidenceType(latest.getEvidenceType())
        .evidenceValue(latest.getEvidenceValue())
        .textValue(reason != null ? reason : "Revoked")
        .quality(latest.getQuality())
        .sourceReviewId(latest.getSourceReviewId())
        .sourceRevision(latest.getSourceRevision())
        .provenance(latest.getProvenance())
        .status("REVOKED")
        .businessOccurredAt(latest.getBusinessOccurredAt())
        .recordedAt(now)
        .build();

    return historyRepository.save(revoked);
  }

  /**
   * Selects valid historical evidence as of a specific point in time (scoringAsOf),
   * enforcing:
   * 1. Append-only revision resolution (latest revision recorded at or before scoringAsOf).
   * 2. Active status (revoked revisions excluded).
   * 3. 24-month historical window (recordedAt >= scoringAsOf - 24 months).
   * 4. Multi-channel Collaboration deduplication: exactly 1 observation per Collaboration.
   */
  @Transactional(readOnly = true)
  public List<HistoricalCampaign> selectValidEvidenceAt(
      UUID creatorId, UUID masterId, LocalDateTime scoringAsOf) {

    if (scoringAsOf == null) {
      scoringAsOf = LocalDateTime.now();
    }
    LocalDateTime cutoff24m = scoringAsOf.minusMonths(24);

    List<HistoricalCampaign> allEntries = new ArrayList<>();
    if (creatorId != null) {
      allEntries.addAll(historyRepository.findByCreatorId(creatorId));
    }
    if (allEntries.isEmpty() && masterId != null) {
      allEntries.addAll(historyRepository.findByMasterId(masterId));
    }

    if (allEntries.isEmpty()) {
      return Collections.emptyList();
    }

    final LocalDateTime effectiveAsOf = scoringAsOf;

    // Group by evidenceRootId (or id if evidenceRootId is null)
    Map<UUID, List<HistoricalCampaign>> byRoot = allEntries.stream()
        .collect(Collectors.groupingBy(h -> h.getEvidenceRootId() != null ? h.getEvidenceRootId() : h.getId()));

    List<HistoricalCampaign> effectiveAtAsOf = new ArrayList<>();
    for (Map.Entry<UUID, List<HistoricalCampaign>> entry : byRoot.entrySet()) {
      // Find the latest revision recorded AT OR BEFORE scoringAsOf
      Optional<HistoricalCampaign> latestBeforeAsOf = entry.getValue().stream()
          .filter(h -> h.getRecordedAt() != null && !h.getRecordedAt().isAfter(effectiveAsOf))
          .max(Comparator.comparing(h -> h.getRevisionNumber() != null ? h.getRevisionNumber() : 1L));

      if (latestBeforeAsOf.isPresent()) {
        HistoricalCampaign candidate = latestBeforeAsOf.get();
        // Check active status and 24-month window based strictly on businessOccurredAt
        boolean isActive = "ACTIVE".equalsIgnoreCase(candidate.getStatus());
        LocalDateTime eventTime = candidate.getBusinessOccurredAt() != null
            ? candidate.getBusinessOccurredAt()
            : candidate.getRecordedAt();
        boolean inWindow = eventTime != null
            && !eventTime.isBefore(cutoff24m)
            && !eventTime.isAfter(effectiveAsOf);

        if (isActive && inWindow) {
          effectiveAtAsOf.add(candidate);
        }
      }
    }

    // Deduplicate by collaborationId: exactly 1 observation per collaboration
    Map<String, HistoricalCampaign> uniqueByCollab = new LinkedHashMap<>();
    for (HistoricalCampaign h : effectiveAtAsOf) {
      String key;
      if (h.getCollaboration() != null && h.getCollaboration().getId() != null) {
        key = "collab:" + h.getCollaboration().getId();
      } else {
        key = "root:" + (h.getEvidenceRootId() != null ? h.getEvidenceRootId() : h.getId());
      }
      uniqueByCollab.putIfAbsent(key, h);
    }

    return new ArrayList<>(uniqueByCollab.values());
  }
}
