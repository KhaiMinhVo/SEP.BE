package com.influencermatch.backend.auth.repository;
import com.influencermatch.backend.auth.model.CreatorInvitation;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CreatorInvitationRepository extends JpaRepository<CreatorInvitation,UUID> {
  Optional<CreatorInvitation> findByEmail(String email);
  Optional<CreatorInvitation> findByTokenHash(String hash);
}
