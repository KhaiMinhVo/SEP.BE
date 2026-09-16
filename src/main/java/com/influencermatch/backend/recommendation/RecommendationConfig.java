package com.influencermatch.backend.recommendation;
import com.influencermatch.backend.entity.User; import jakarta.persistence.*; import lombok.*; import java.math.BigDecimal; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="recommendationConfig") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class RecommendationConfig {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="configId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="userId",nullable=false) private User user;
 @Column(nullable=false,length=50) private String formulaVersion;
 @Column(nullable=false,precision=5,scale=4) private BigDecimal publicMetricWeight; @Column(nullable=false,precision=5,scale=4) private BigDecimal campaignContextWeight;
 @Column(nullable=false,precision=5,scale=4) private BigDecimal brandContextWeight; @Column(nullable=false,precision=5,scale=4) private BigDecimal historicalCampaignWeight;
 @Column(nullable=false) private boolean active; @Column(nullable=false) private boolean defaultConfig;
 @Column(nullable=false) private LocalDateTime createdAt; @Column(nullable=false) private LocalDateTime updatedAt;
}
