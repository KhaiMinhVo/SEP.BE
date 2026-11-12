package com.influencermatch.backend.brand.repository;

import com.influencermatch.backend.brand.controller.*;
import com.influencermatch.backend.brand.dto.*;
import com.influencermatch.backend.brand.enums.*;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.service.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandProfileRepository extends JpaRepository<BrandProfile, UUID> {
  Optional<BrandProfile> findByUserId(UUID userId);

  boolean existsByUserId(UUID userId);

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select b from BrandProfile b where b.id = :id")
  Optional<BrandProfile> lockForBilling(
      @org.springframework.data.repository.query.Param("id") UUID id);
}
