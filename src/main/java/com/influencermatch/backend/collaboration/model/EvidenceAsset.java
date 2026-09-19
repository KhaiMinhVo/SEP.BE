package com.influencermatch.backend.collaboration.model;
import com.influencermatch.backend.collaboration.model.*;
import com.influencermatch.backend.collaboration.repository.*;
import com.influencermatch.backend.collaboration.enums.*;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name = "evidence_asset") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class EvidenceAsset {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(nullable=false) private CampaignOutcome outcome;
 @Column(nullable=false,length=50) private String assetType; @Column(nullable=false,length=1000) private String storageUrl;
 @Column(length=100) private String source; @Column(nullable=false) private LocalDateTime uploadedAt;
}


