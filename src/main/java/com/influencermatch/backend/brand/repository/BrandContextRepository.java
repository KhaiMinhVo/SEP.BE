package com.influencermatch.backend.brand.repository;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.repository.*;
import com.influencermatch.backend.brand.service.*;
import com.influencermatch.backend.brand.enums.*;
import com.influencermatch.backend.brand.dto.*;
import com.influencermatch.backend.brand.controller.*;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface BrandContextRepository extends JpaRepository<BrandContext, UUID> {
    Optional<BrandContext> findByBrandProfileId(UUID brandProfileId);
}


