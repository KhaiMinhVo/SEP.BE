package com.influencermatch.backend.collaboration.model;
import com.influencermatch.backend.collaboration.model.*;
import com.influencermatch.backend.collaboration.repository.*;
import com.influencermatch.backend.collaboration.enums.*;
import jakarta.persistence.*; import lombok.*; import java.math.BigDecimal; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name = "campaign_outcome", uniqueConstraints=@UniqueConstraint(name="ux_outcome_collaboration",columnNames="collaboration_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class CampaignOutcome {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column private UUID id;
 @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(nullable=false) private Collaboration collaboration;
 @Column(length=100) private String kpiType; @Column(precision=19,scale=2) private BigDecimal kpiTarget; @Column(precision=19,scale=2) private BigDecimal kpiActual;
 private String resultNote; private LocalDateTime recordedAt; @Column(precision=19,scale=2) private BigDecimal actualFee; @Column(length=3) private String currency;
 @Column(precision=19,scale=2) private BigDecimal revenue; @Column(precision=12,scale=4) private BigDecimal roi;
 @Column(length=32) private String evidenceQuality; private LocalDateTime completedAt;
}


