package com.influencermatch.backend.creator;
import jakarta.persistence.*; import lombok.*; import java.util.UUID;
@Entity @Table(name="platform",uniqueConstraints={@UniqueConstraint(name="uxPlatformName",columnNames="name"),@UniqueConstraint(name="uxPlatformProviderCode",columnNames="providerCode")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class PlatformEntity {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="platformId") private UUID id;
 @Column(nullable=false,length=50) private String name; @Column(nullable=false,length=50) private String providerCode; @Column(nullable=false) private boolean active;
}
