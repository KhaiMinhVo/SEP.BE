package com.influencermatch.backend.creator;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="creatorPlatformAccount",uniqueConstraints=@UniqueConstraint(name="uxCreatorAccountExternal",columnNames={"platformId","externalId"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class CreatorPlatformAccount {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="creatorAccountId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="creatorId",nullable=false) private Creator creator;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="platformId",nullable=false) private PlatformEntity platform;
 @Column(nullable=false,length=255) private String externalId; @Column(nullable=false,length=255) private String username;
 @Column(length=1000) private String profileUrl; private LocalDateTime lastRefreshedAt;
}
