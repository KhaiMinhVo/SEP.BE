package com.influencermatch.backend.billing.repository;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.dto.*;
import com.influencermatch.backend.billing.controller.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.List;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    List<Subscription> findByBrandProfileId(UUID brandProfileId);
}
