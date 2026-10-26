package com.influencermatch.backend.creator.email;

import com.influencermatch.backend.common.BaseEntity;
import com.influencermatch.backend.creator.model.CreatorInvitation;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity @Table(name="claim_email_outbox")
@Getter @Setter @NoArgsConstructor @SuperBuilder
public class ClaimEmailJob extends BaseEntity {
  @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="invitation_id",nullable=false,unique=true)
  private CreatorInvitation invitation;
  @Column(name="payload_ciphertext",columnDefinition="text") private String payloadCiphertext;
  @Column(nullable=false,length=64) private String tokenHash;
  @Column(nullable=false,length=20) private String status;
  private int attempts;
  private int cycleAttempts;
  private LocalDateTime nextAttemptAt,lastAttemptAt,lastSentAt,expiresAt,leaseUntil;
  private UUID leaseToken;
  @ElementCollection @CollectionTable(name="claim_email_retry",joinColumns=@JoinColumn(name="job_id"))
  @Column(name="requested_at",nullable=false) @Builder.Default
  private List<LocalDateTime> manualRetryTimes=new ArrayList<>();
}
