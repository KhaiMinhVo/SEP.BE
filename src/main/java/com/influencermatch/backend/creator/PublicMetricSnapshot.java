package com.influencermatch.backend.creator;
import com.fasterxml.jackson.databind.JsonNode; import jakarta.persistence.*; import lombok.*; import org.hibernate.annotations.JdbcTypeCode; import org.hibernate.type.SqlTypes; import java.math.BigDecimal; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="publicMetricSnapshot") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class PublicMetricSnapshot {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="snapshotId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="creatorAccountId",nullable=false) private CreatorPlatformAccount creatorAccount;
 private Long followers; @Column(precision=19,scale=2) private BigDecimal avgViews; @Column(precision=19,scale=2) private BigDecimal avgLikes;
 @Column(precision=19,scale=2) private BigDecimal avgComments; @Column(precision=19,scale=2) private BigDecimal avgShares;
 @Column(precision=7,scale=4) private BigDecimal engagementRate; @JdbcTypeCode(SqlTypes.JSON) private JsonNode recentActivity;
 private String contentSummary; @Column(nullable=false,length=100) private String source;
 @Column(precision=5,scale=4) private BigDecimal sourceConfidence; @Column(nullable=false) private LocalDateTime collectedAt;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private MetricDataStatus dataStatus;
}
