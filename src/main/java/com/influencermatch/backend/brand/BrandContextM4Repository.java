package com.influencermatch.backend.brand;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface BrandContextM4Repository extends JpaRepository<BrandContextM4, UUID> {
    Optional<BrandContextM4> findByBrandProfileId(UUID brandProfileId);
}
