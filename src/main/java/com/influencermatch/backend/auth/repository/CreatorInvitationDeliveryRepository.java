package com.influencermatch.backend.auth.repository;
import com.influencermatch.backend.auth.model.CreatorInvitationDelivery;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CreatorInvitationDeliveryRepository extends JpaRepository<CreatorInvitationDelivery,UUID> {
  long countByEmailAndSentAtAfter(String email,Instant since);
  void deleteByEmailAndSentAtBefore(String email,Instant before);
}
