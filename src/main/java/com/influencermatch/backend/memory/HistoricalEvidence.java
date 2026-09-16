package com.influencermatch.backend.memory;
import com.fasterxml.jackson.databind.JsonNode; import com.influencermatch.backend.collaboration.*; import com.influencermatch.backend.creator.Creator; import jakarta.persistence.*; import lombok.*; import org.hibernate.annotations.JdbcTypeCode; import org.hibernate.type.SqlTypes; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="historicalEvidence") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class HistoricalEvidence {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="historicalEvidenceId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="creatorId",nullable=false) private Creator creator;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="collaborationId") private Collaboration collaboration;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="outcomeId") private CampaignOutcome outcome;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private EvidenceType evidenceType;
 @JdbcTypeCode(SqlTypes.JSON) private JsonNode evidenceValue; private String textValue;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private EvidenceQuality evidenceQuality; @Column(nullable=false) private LocalDateTime generatedAt;
}
