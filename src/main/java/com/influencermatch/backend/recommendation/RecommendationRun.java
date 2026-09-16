package com.influencermatch.backend.recommendation;
import com.influencermatch.backend.campaign.*; import com.influencermatch.backend.brand.BrandContextM4; import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="recommendationRun") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class RecommendationRun {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="recommendationRunId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="campaignId",nullable=false) private Campaign campaign;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="campaignContextId",nullable=false) private CampaignContextM3 campaignContext;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="brandContextId",nullable=false) private BrandContextM4 brandContext;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="configId",nullable=false) private RecommendationConfig config;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private RecommendationRunStatus status;
 @Column(nullable=false) private int candidateCount; @Column(nullable=false) private int eligibleCount; @Column(nullable=false) private int resultCount;
 private LocalDateTime startedAt; private LocalDateTime completedAt;
}
