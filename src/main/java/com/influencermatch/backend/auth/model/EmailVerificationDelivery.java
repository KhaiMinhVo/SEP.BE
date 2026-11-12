package com.influencermatch.backend.auth.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity @Table(name="email_verification_delivery") @Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class EmailVerificationDelivery {
  @Id private UUID id;
  @Column(nullable=false) private UUID userId;
  @Column(nullable=false) private Instant sentAt;
}
