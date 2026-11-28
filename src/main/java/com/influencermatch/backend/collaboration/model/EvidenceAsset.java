package com.influencermatch.backend.collaboration.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "evidence_asset")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvidenceAsset {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "collaboration_id")
  private Collaboration collaboration;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "outcome_id")
  private CampaignOutcome outcome;

  @Column(nullable = false, length = 50)
  private String assetType;

  @Column(nullable = false, length = 2048)
  private String storageUrl;

  @Column(length = 500) private String storageKey;
  @Column(length = 100) private String mimeType;
  private Long sizeBytes;
  private LocalDateTime imageUpdatedAt;

  @Column(length = 100)
  private String source;

  @Column(nullable = false)
  private LocalDateTime uploadedAt;
}
