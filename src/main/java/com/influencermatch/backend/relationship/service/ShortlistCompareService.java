package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.creator.dto.CreatorClaimDtos.*;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.relationship.dto.CompareDtos.*;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.*;
import jakarta.validation.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor
public class ShortlistCompareService {
  private final CampaignRepository campaigns;
  private final ShortlistItemRepository shortlist;
  private final PublicCreatorMetricRepository metrics;
  private final CreatorRateCardRepository cards;
  private final Validator validator;

  @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
  @PreAuthorize("hasAuthority('MANAGE_OWN_SHORTLIST')")
  public Response compare(UUID campaignId, Request request) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    var violations=validator.validate(request);
    if(!violations.isEmpty()) throw new ConstraintViolationException(violations);
    var campaign=campaigns.findById(campaignId)
        .filter(c->c.getBrandProfile().getUser().getId().equals(Permissions.actor().getId()))
        .orElseThrow(()->new NotFoundException("Campaign not found"));
    var ids=request.creatorIds();
    if(new HashSet<>(ids).size()!=ids.size()) throw new com.influencermatch.backend.exception.ValidationException("Creator IDs must be distinct");
    var items=shortlist.findByCampaignIdAndCreatorIdIn(campaign.getId(),ids).stream()
        .collect(Collectors.toMap(i->i.getCreator().getId(),i->i));
    if(items.size()!=ids.size()) throw new com.influencermatch.backend.exception.ValidationException("Every Creator must belong to this campaign shortlist");
    var snapshots=metrics.latestForCreators(ids).stream().collect(Collectors.toMap(m->m.getCreator().getId(),m->m));
    var rates=cards.visibleForCreators(ids).stream().collect(Collectors.groupingBy(r->r.getClaim().getCreator().getId(),
        Collectors.mapping(r->new RateItem(r.getContentType(),r.getPrice(),r.getCurrency(),r.getNote()),Collectors.toList())));
    return new Response(campaignId,ids.stream().map(id->{
      var s=items.get(id); var c=s.getCreator(); var m=snapshots.get(id);
      boolean verified=c.getSourceType()==CreatorSourceType.AUTHORIZED && c.getOwner()!=null;
      return new Item(id,c.getPlatform(),c.getUserName(),c.getDisplayName(),c.getProfileUrl(),c.getAvatarUrl(),c.getLocation(),
          m==null?null:m.getCreatorType(),s.getPriority(),s.getNote(),c.getSourceType(),verified,c.getAuthorizedAt(),
          m==null?null:new Metrics(m.getId(),m.getNiche(),m.getFollowers(),m.getAvgViews(),m.getAvgLikes(),m.getAvgComments(),
              m.getAvgShares(),m.getEngagementRate(),m.getDataConfidence(),m.getFreshnessStatus(),m.getCollectedAt()),
          new RateResponse(id,"CREATOR_DECLARED",verified?rates.getOrDefault(id,List.of()):List.of()));
    }).toList());
  }
}
