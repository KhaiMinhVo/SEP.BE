package com.influencermatch.backend.collaboration;
import com.influencermatch.backend.campaign.Campaign; import com.influencermatch.backend.creator.Creator; import com.influencermatch.backend.relationship.BrandCreatorRelationship; import jakarta.persistence.*; import lombok.*; import java.math.BigDecimal; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="collaboration",uniqueConstraints=@UniqueConstraint(name="uxCollaborationRelationship",columnNames="relationshipId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class Collaboration {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="collaborationId") private UUID id;
 @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="relationshipId",nullable=false) private BrandCreatorRelationship relationship;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="campaignId",nullable=false) private Campaign campaign;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="creatorId",nullable=false) private Creator creator;
 @Column(precision=19,scale=2) private BigDecimal agreedFee; @Column(length=3) private String currency;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private CollaborationStatus status; @Column(length=1000) private String publishedContentUrl;
 private LocalDateTime startAt; private LocalDateTime completedAt; private String note;
 @Enumerated(EnumType.STRING) @Column(length=32) private CollaborationPaymentStatus paymentStatus; @Column(nullable=false) private LocalDateTime updatedAt;
}
