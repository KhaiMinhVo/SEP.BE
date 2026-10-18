package com.influencermatch.backend.creator.model;
import com.influencermatch.backend.common.BaseEntity;
import com.influencermatch.backend.creator.enums.CreatorClaimStatus;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="creator_claim")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class CreatorClaim extends BaseEntity {
  @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(nullable=false) private Creator creator;
  @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(nullable=false) private User claimant;
  @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false) private List<String> evidenceUrls;
  @Column(nullable=false,length=2000) private String note;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private CreatorClaimStatus status;
  @ManyToOne(fetch=FetchType.LAZY) private User reviewer;
  @Column(length=2000) private String reason;
  private LocalDateTime reviewedAt;
  @ManyToOne(fetch=FetchType.LAZY) private User revokedBy;
  @Column(length=2000) private String revokeReason;
  private LocalDateTime revokedAt;
}
