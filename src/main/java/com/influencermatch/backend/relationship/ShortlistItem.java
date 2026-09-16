package com.influencermatch.backend.relationship;
import com.influencermatch.backend.campaign.Campaign; import com.influencermatch.backend.creator.Creator; import com.influencermatch.backend.entity.User; import com.influencermatch.backend.recommendation.RecommendationResult; import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="shortlistItem",uniqueConstraints=@UniqueConstraint(name="uxShortlistCampaignCreator",columnNames={"campaignId","creatorId"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class ShortlistItem {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="shortlistItemId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="campaignId",nullable=false) private Campaign campaign;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="creatorId",nullable=false) private Creator creator;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="recommendationResultId") private RecommendationResult recommendationResult;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="userId",nullable=false) private User user;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private ShortlistPriority priority; private String note; @Column(nullable=false) private LocalDateTime addedAt;
}
