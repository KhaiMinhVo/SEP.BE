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

  @Column(name = "recorded_at", nullable = false)
  private LocalDateTime recordedAt;
}
