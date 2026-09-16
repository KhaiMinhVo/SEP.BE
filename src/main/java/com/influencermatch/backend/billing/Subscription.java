package com.influencermatch.backend.billing;
import com.influencermatch.backend.brand.BrandProfile; import jakarta.persistence.*; import lombok.*; import java.time.LocalDate; import java.util.UUID;
@Entity @Table(name="subscription") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class Subscription {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="subscriptionId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="brandProfileId",nullable=false) private BrandProfile brandProfile;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="planId",nullable=false) private Plan plan;
 @Column(nullable=false) private LocalDate startDate; @Column(nullable=false) private LocalDate expirationDate;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private SubscriptionStatus status;
 @Column(nullable=false) private int usedCampaignQuota; @Column(nullable=false) private int usedRecommendationQuota; @Column(nullable=false) private int usedRefreshQuota;
}
