package com.influencermatch.backend.auth.repository;
import com.influencermatch.backend.auth.model.CreatorRegistrationInvitation;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CreatorRegistrationInvitationRepository extends JpaRepository<CreatorRegistrationInvitation,UUID> {
  Optional<CreatorRegistrationInvitation> findByEmail(String email);
  Optional<CreatorRegistrationInvitation> findByTokenHash(String hash);
}
