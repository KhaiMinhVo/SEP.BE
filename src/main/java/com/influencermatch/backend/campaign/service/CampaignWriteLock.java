package com.influencermatch.backend.campaign.service;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class CampaignWriteLock {
  private final UserRepository users;
  private final CampaignRepository campaigns;
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

  @Transactional(propagation = Propagation.MANDATORY)
  public Campaign owned(UUID id) {
    var actor = Permissions.actor();
    var user =
        users
            .findLockedByEmail(actor.getEmail())
            .orElseThrow(() -> new ForbiddenException("Brand required"));
    if (user.getRole() != com.influencermatch.backend.user.enums.Role.BRAND
        || user.getStatus() != com.influencermatch.backend.user.enums.UserStatus.ACTIVE)
      throw new ForbiddenException("Active Brand required");
    var c =
        campaigns.findLockedById(id).orElseThrow(() -> new NotFoundException("Campaign not found"));
    em.refresh(c);
    if (!c.getBrandProfile().getUser().getId().equals(user.getId()))
      throw new ForbiddenException("You cannot access another Brand campaign");
    return c;
  }
}
