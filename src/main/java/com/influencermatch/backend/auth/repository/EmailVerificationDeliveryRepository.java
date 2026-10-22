package com.influencermatch.backend.auth.repository;
import com.influencermatch.backend.auth.model.EmailVerificationDelivery;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface EmailVerificationDeliveryRepository extends JpaRepository<EmailVerificationDelivery,UUID> {
  long countByUserIdAndSentAtAfter(UUID userId,Instant since);
  void deleteByUserIdAndSentAtBefore(UUID userId,Instant before);
}
