package com.influencermatch.backend.support;

import static org.mockito.Mockito.*;

import com.influencermatch.backend.campaign.repository.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.relationship.repository.*;
import com.influencermatch.backend.relationship.service.*;
import java.util.*;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit-only collaborators bridge old repository fixtures to the new transaction boundaries. */
public final class TestShortlistFixtures {
  public static void wire(ShortlistServiceImpl service) {
    var items =
        (ShortlistItemRepository) ReflectionTestUtils.getField(service, "shortlistItemRepository");
    var metrics =
        (PublicCreatorMetricRepository)
            ReflectionTestUtils.getField(service, "publicCreatorMetricRepository");
    var creators = (CreatorRepository) ReflectionTestUtils.getField(service, "creatorRepository");
    var ownership = (CampaignOwnershipService) ReflectionTestUtils.getField(service, "ownership");
    var runs =
        (RecommendationRunRepository)
            ReflectionTestUtils.getField(service, "recommendationRunRepository");
    lenient()
        .when(items.findLockedByCampaignIdAndCreatorId(any(), any()))
        .thenAnswer(i -> items.findByCampaignIdAndCreatorId(i.getArgument(0), i.getArgument(1)));
    lenient()
        .when(items.findLockedByIdAndCampaignId(any(), any()))
        .thenAnswer(i -> items.findByIdAndCampaignId(i.getArgument(0), i.getArgument(1)));
    lenient().when(items.saveAndFlush(any())).thenAnswer(i -> items.save(i.getArgument(0)));
    lenient()
        .when(metrics.latestForCreators(anyCollection()))
        .thenAnswer(
            i -> {
              List<com.influencermatch.backend.creator.model.PublicCreatorMetric> out =
                  new ArrayList<>();
              for (var id : (Collection<java.util.UUID>) i.getArgument(0)) {
                var m = metrics.findByCreatorId(id);
                if (m != null)
                  m.ifPresent(
                      v -> {
                        if (v.getCreator() == null)
                          v.setCreator(
                              com.influencermatch.backend.creator.model.Creator.builder()
                                  .id(id)
                                  .build());
                        out.add(v);
                      });
              }
              return out;
            });
    var locks = mock(com.influencermatch.backend.campaign.service.CampaignWriteLock.class);
    lenient()
        .when(locks.owned(any()))
        .thenAnswer(
            i -> {
              var c = ownership.requireOwned(i.getArgument(0));
              if (c != null && c.getBrandProfile() == null)
                c.setBrandProfile(
                    com.influencermatch.backend.brand.model.BrandProfile.builder()
                        .user(com.influencermatch.backend.security.permission.Permissions.actor())
                        .build());
              return c;
            });
    var projection = new ShortlistProjectionService(metrics);
    var writer =
        new ShortlistItemWriter(
            ownership,
            locks,
            items,
            creators,
            runs,
            projection,
            java.time.Clock.systemUTC(),
            mock(org.springframework.context.ApplicationEventPublisher.class));
    var contactPolicy=mock(com.influencermatch.backend.creator.portal.CreatorPortalProjection.class);
    lenient().when(contactPolicy.canViewContacts()).thenAnswer(i->{try{com.influencermatch.backend.security.permission.Permissions.require(com.influencermatch.backend.security.permission.Permission.VIEW_CREATOR_CONTACT);return true;}catch(com.influencermatch.backend.exception.ForbiddenException e){return false;}});
    ReflectionTestUtils.setField(service,"contactPolicy",contactPolicy);
    ReflectionTestUtils.setField(service, "projection", projection);
    ReflectionTestUtils.setField(service, "writer", writer);
    ReflectionTestUtils.setField(service, "writeLocks", locks);
  }
}
