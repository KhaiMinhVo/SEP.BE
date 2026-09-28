package com.influencermatch.backend.repository;

import com.influencermatch.backend.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, UUID> {
    Optional<ExternalIdentity> findByProviderAndProviderSubject(ExternalIdentityProvider provider, String providerSubject);
    Optional<ExternalIdentity> findByUserIdAndProvider(UUID userId, ExternalIdentityProvider provider);
}
