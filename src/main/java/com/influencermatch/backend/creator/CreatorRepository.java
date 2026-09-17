package com.influencermatch.backend.creator;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreatorRepository extends JpaRepository<Creator, UUID> {
    Optional<Creator> findByPlatformAndExternalId(String platform, String externalId);
}
