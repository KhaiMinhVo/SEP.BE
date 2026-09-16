package com.influencermatch.backend.memory;
import com.fasterxml.jackson.databind.JsonNode; import com.influencermatch.backend.brand.BrandProfile; import jakarta.persistence.*; import lombok.*; import org.hibernate.annotations.JdbcTypeCode; import org.hibernate.type.SqlTypes; import java.math.BigDecimal; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="brandPattern") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class BrandPattern {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="brandPatternId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="brandProfileId",nullable=false) private BrandProfile brandProfile;
 @Column(length=50) private String platform; @Column(length=120) private String niche;
 @Column(precision=7,scale=4) private BigDecimal averageSuccessfulScore; @Column(precision=19,scale=2) private BigDecimal averageAgreedFee;
 @Column(length=3) private String currency; @Column(precision=7,scale=4) private BigDecimal preferredEngagementRate;
 @Column(nullable=false,length=100) private String patternType; @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false) private JsonNode patternValue;
 @Column(nullable=false,precision=5,scale=4) private BigDecimal confidence; @Column(nullable=false) private int evidenceCount; @Column(nullable=false) private LocalDateTime learnedAt;
}
