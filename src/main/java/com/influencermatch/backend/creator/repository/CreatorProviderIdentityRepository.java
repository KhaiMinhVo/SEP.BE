package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.model.CreatorProviderIdentity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CreatorProviderIdentityRepository
    extends JpaRepository<CreatorProviderIdentity, UUID>,
        JpaSpecificationExecutor<CreatorProviderIdentity> {

  Optional<CreatorProviderIdentity> findByProviderAndProviderUserId(
      String provider, String providerUserId);

  List<CreatorProviderIdentity> findByCreatorId(UUID creatorId);

  boolean existsByProviderAndProviderUserId(String provider, String providerUserId);
}
