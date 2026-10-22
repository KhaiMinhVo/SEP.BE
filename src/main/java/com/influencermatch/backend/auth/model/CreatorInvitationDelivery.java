package com.influencermatch.backend.auth.model;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
@Entity @Table(name="creator_invitation_delivery") @Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class CreatorInvitationDelivery {
  @Id private UUID id;
  @Column(nullable=false,length=255) private String email;
  @Column(nullable=false) private Instant sentAt;
}
