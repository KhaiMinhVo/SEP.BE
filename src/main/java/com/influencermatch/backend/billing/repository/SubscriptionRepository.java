package com.influencermatch.backend.billing.repository;

import com.influencermatch.backend.billing.controller.*;
import com.influencermatch.backend.billing.dto.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.service.*;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
  List<Subscription> findByBrandProfileId(UUID brandProfileId);

  boolean existsByBrandProfileIdAndStatusAndExpirationDateGreaterThanEqual(
      UUID brandProfileId, SubscriptionStatus status, java.time.LocalDate today);
}
