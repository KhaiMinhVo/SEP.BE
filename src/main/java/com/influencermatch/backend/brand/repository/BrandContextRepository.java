package com.influencermatch.backend.brand.repository;

import com.influencermatch.backend.brand.controller.*;
import com.influencermatch.backend.brand.dto.*;
import com.influencermatch.backend.brand.enums.*;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.service.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandContextRepository extends JpaRepository<BrandContext, UUID> {
  Optional<BrandContext> findByBrandProfileId(UUID brandProfileId);
}
