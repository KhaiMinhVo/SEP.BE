package com.influencermatch.backend.brand;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface BrandContextRepository extends JpaRepository<BrandContext, UUID> {
    Optional<BrandContext> findByBrandProfileId(UUID brandProfileId);
}


