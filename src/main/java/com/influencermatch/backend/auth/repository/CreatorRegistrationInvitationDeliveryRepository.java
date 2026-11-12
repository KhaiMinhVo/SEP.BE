package com.influencermatch.backend.auth.repository;
import com.influencermatch.backend.auth.model.CreatorRegistrationInvitationDelivery;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CreatorRegistrationInvitationDeliveryRepository extends JpaRepository<CreatorRegistrationInvitationDelivery,UUID> {
  long countByEmailAndSentAtAfter(String email,Instant since);
  void deleteByEmailAndSentAtBefore(String email,Instant before);
}
