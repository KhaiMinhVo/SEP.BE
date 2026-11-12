package com.influencermatch.backend.brand.repository;

import com.influencermatch.backend.brand.model.BrandPattern;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BrandPatternRepository extends JpaRepository<BrandPattern, UUID> {
  List<BrandPattern> findByBrandProfileId(UUID brandProfileId);
  Optional<BrandPattern> findByBrandProfileIdAndPatternType(UUID brandProfileId, String patternType);
  List<BrandPattern> findByBrandProfileIdAndPlatform(UUID brandProfileId, String platform);
}
