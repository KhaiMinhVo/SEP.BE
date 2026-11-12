package com.influencermatch.backend.auth.repository;

import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.repository.UserRepository;

import com.influencermatch.backend.auth.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, UUID> {
    Optional<ExternalIdentity> findByProviderAndProviderSubject(ExternalIdentityProvider provider, String providerSubject);
    Optional<ExternalIdentity> findByUserIdAndProvider(UUID userId, ExternalIdentityProvider provider);
}
