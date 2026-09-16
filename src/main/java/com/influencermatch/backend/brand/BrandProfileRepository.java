package com.influencermatch.backend.brand;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface BrandProfileRepository extends JpaRepository<BrandProfile, UUID> {
    Optional<BrandProfile> findByUserId(UUID userId);
    boolean existsByUserId(UUID userId);
}
