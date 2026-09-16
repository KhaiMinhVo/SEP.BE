package com.influencermatch.backend.recommendation;
import com.fasterxml.jackson.databind.JsonNode; import com.influencermatch.backend.creator.*; import jakarta.persistence.*; import lombok.*; import org.hibernate.annotations.JdbcTypeCode; import org.hibernate.type.SqlTypes; import java.math.BigDecimal; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="recommendationResult",uniqueConstraints={@UniqueConstraint(name="uxRecommendationResultCreator",columnNames={"recommendationRunId","creatorId"}),@UniqueConstraint(name="uxRecommendationResultRank",columnNames={"recommendationRunId","rankPosition"})})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class RecommendationResult {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="resultId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="recommendationRunId",nullable=false) private RecommendationRun recommendationRun;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="creatorId",nullable=false) private Creator creator;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="snapshotId") private PublicMetricSnapshot snapshot;
 @Column(nullable=false,precision=7,scale=4) private BigDecimal matchScore; @Column(nullable=false,precision=5,scale=4) private BigDecimal confidence;
 @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false) private JsonNode scoreBreakdown; @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false) private JsonNode missingEvidence;
 @Column(precision=19,scale=2) private BigDecimal estimatedFeeMin; @Column(precision=19,scale=2) private BigDecimal estimatedFeeMax;
 @Column(length=3) private String feeCurrency; @Enumerated(EnumType.STRING) @Column(length=32) private FeeConfidence feeConfidence;
 private String explanation; @Column(nullable=false) private int rankPosition; @Column(nullable=false) private LocalDateTime generatedAt;
}
