package com.influencermatch.backend.collaboration;
import com.influencermatch.backend.entity.User; import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="creatorReview",uniqueConstraints={@UniqueConstraint(name="uxCreatorReviewOutcome",columnNames="outcomeId"),@UniqueConstraint(name="uxCreatorReviewCollaboration",columnNames="collaborationId")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class CreatorReview {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="reviewId") private UUID id;
 @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="outcomeId",nullable=false) private CampaignOutcome outcome;
 @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="collaborationId",nullable=false) private Collaboration collaboration;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="reviewedByUserId",nullable=false) private User reviewedBy;
 @Column(nullable=false) private short overallRating; @Column(nullable=false) private short brandFitRating; private Boolean wouldCollaborateAgain;
 private String note; @Column(nullable=false) private LocalDateTime reviewedAt;
}
