package com.influencermatch.backend.creator.model;
import com.influencermatch.backend.creator.enums.RateCardContentType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
@Entity @Table(name="creator_rate_card",uniqueConstraints=@UniqueConstraint(columnNames={"claim_id","content_type"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CreatorRateCard {
  @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
  @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(nullable=false) private CreatorClaim claim;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private RateCardContentType contentType;
  @Column(nullable=false,precision=19,scale=2) private BigDecimal price;
  @Column(nullable=false,length=3) private String currency;
  @Column(length=1000) private String note;
  @Column(nullable=false) private LocalDateTime updatedAt;
}
