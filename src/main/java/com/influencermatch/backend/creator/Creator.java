package com.influencermatch.backend.creator;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="creator") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class Creator {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="creatorId") private UUID id;
 @Column(nullable=false,length=200) private String displayName; @Column(length=150) private String location;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private CreatorStatus status;
 @Column(nullable=false) private LocalDateTime firstDiscoveredAt; @Column(nullable=false) private LocalDateTime updatedAt;
}
