package com.influencermatch.backend.billing;
import jakarta.persistence.*; import lombok.*; import java.math.BigDecimal; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="plan",uniqueConstraints=@UniqueConstraint(name="uxPlanName",columnNames="planName")) @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class Plan {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="planId") private UUID id;
 @Column(nullable=false,length=100) private String planName; @Column(nullable=false,precision=19,scale=2) private BigDecimal price; @Column(nullable=false,length=3) private String currency;
 @Column(nullable=false) private int durationDays; @Column(nullable=false) private int campaignQuota; @Column(nullable=false) private int recommendationQuota; @Column(nullable=false) private int refreshQuota;
 private String description; @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private PlanStatus status; @Column(nullable=false) private LocalDateTime createdAt;
}
