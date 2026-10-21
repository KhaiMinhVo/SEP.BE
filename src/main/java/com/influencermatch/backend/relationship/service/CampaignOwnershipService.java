package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.exception.NotFoundException;
import com.influencermatch.backend.security.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class CampaignOwnershipService {
  private final CampaignRepository campaigns;
  public Campaign requireOwned(UUID id) {
    Permissions.require(Permission.VIEW_OWN_CAMPAIGN);
    UUID actorId=Permissions.actor().getId();
    return campaigns.findById(id).filter(c->c.getBrandProfile().getUser().getId().equals(actorId))
        .orElseThrow(()->new NotFoundException("Campaign not found"));
  }
}
