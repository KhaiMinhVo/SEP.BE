package com.influencermatch.backend.campaign.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.collaboration.model.CampaignOutcome;
import com.influencermatch.backend.collaboration.model.Collaboration;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorMaster;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "historical_campaign")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HistoricalCampaign {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "creator_id", nullable = false)
  private Creator creator;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "master_id")
  private CreatorMaster master;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "collaboration_id")
  private Collaboration collaboration;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "outcome_id")
  private CampaignOutcome outcome;

  @Column(name = "evidence_type", nullable = false, length = 32)
  private String evidenceType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "evidence_value")
  private JsonNode evidenceValue;

  @Column(name = "text_value", columnDefinition = "TEXT")
  private String textValue;

  @Column(nullable = false, length = 32)
  private String quality;

  private UUID sourceReviewId;
  private Long sourceRevision;
  @Builder.Default private String provenance = "LEGACY_UNVERIFIED";

  @Column(name = "evidence_root_id", nullable = false)
  private UUID evidenceRootId;

  @Builder.Default
  @Column(name = "revision_number", nullable = false)
  private Long revisionNumber = 1L;

  @Builder.Default
  @Column(name = "schema_version", nullable = false, length = 16)
  private String schemaVersion = "1.0";

  @Column(name = "supersedes_id")
  private UUID supersedesId;

  @Column(name = "business_occurred_at")
  private LocalDateTime businessOccurredAt;

  @Builder.Default
  @Column(name = "status", nullable = false, length = 32)
  private String status = "ACTIVE";

  @Column(name = "recorded_at", nullable = false)
  private LocalDateTime recordedAt;

  @PrePersist
  public void prePersist() {
    if (evidenceRootId == null) {
      evidenceRootId = id != null ? id : UUID.randomUUID();
    }
    if (businessOccurredAt == null) {
      businessOccurredAt = recordedAt != null ? recordedAt : LocalDateTime.now();
    }
    if (status == null) {
      status = "ACTIVE";
    }
    if (revisionNumber == null) {
      revisionNumber = 1L;
    }
    if (schemaVersion == null) {
      schemaVersion = "1.0";
    }
  }
}
