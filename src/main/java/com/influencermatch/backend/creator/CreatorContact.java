package com.influencermatch.backend.creator;
import com.influencermatch.backend.entity.User; import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="creatorContact") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class CreatorContact {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="contactId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="creatorAccountId",nullable=false) private CreatorPlatformAccount creatorAccount;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="addedByUserId") private User addedBy;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private ContactType contactType; @Column(nullable=false,length=500) private String contactValue;
 @Column(nullable=false,length=100) private String source; @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private ContactVisibility visibility;
 @Column(nullable=false) private LocalDateTime createdAt;
}
