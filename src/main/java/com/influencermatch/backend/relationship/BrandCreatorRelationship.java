package com.influencermatch.backend.relationship;
import com.influencermatch.backend.campaign.Campaign; import com.influencermatch.backend.creator.Creator; import com.influencermatch.backend.entity.User; import jakarta.persistence.*; import lombok.*; import java.math.BigDecimal; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="relationship",uniqueConstraints=@UniqueConstraint(name="uxRelationshipCampaignCreator",columnNames={"campaignId","creatorId"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class BrandCreatorRelationship {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="relationshipId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="userId",nullable=false) private User user;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="creatorId",nullable=false) private Creator creator;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="campaignId",nullable=false) private Campaign campaign;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="shortlistItemId") private ShortlistItem shortlistItem;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private RelationshipStage stage; @Column(length=100) private String contactMethod;
 @Column(precision=19,scale=2) private BigDecimal quotedFee; @Column(precision=19,scale=2) private BigDecimal agreedFee; @Column(length=3) private String currency;
 private LocalDateTime lastContactAt; private String notes; @Column(length=500) private String nextAction;
 @Column(nullable=false) private LocalDateTime createdAt; @Column(nullable=false) private LocalDateTime updatedAt;
}
