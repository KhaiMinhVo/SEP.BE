package com.influencermatch.backend.relationship;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.relationship.dto.CompareDtos.Request;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.relationship.service.ShortlistCompareService;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import jakarta.validation.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;

class ShortlistCompareServiceTest {
  final CampaignRepository campaigns=mock(CampaignRepository.class);
  final ShortlistItemRepository shortlist=mock(ShortlistItemRepository.class);
  final PublicCreatorMetricRepository metrics=mock(PublicCreatorMetricRepository.class);
  final ValidatorFactory factory=Validation.buildDefaultValidatorFactory();
  final ShortlistCompareService service=new ShortlistCompareService(campaigns,shortlist,metrics,factory.getValidator());
  final com.influencermatch.backend.user.model.User owner=TestActors.user(Role.BRAND);
  @BeforeEach void login(){TestActors.authenticate(owner);}
  @AfterEach void clear(){SecurityContextHolder.clearContext();factory.close();}
  Campaign campaign(){var c=Campaign.builder().id(UUID.randomUUID()).brandProfile(BrandProfile.builder().user(owner).build()).build();
    when(campaigns.findById(c.getId())).thenReturn(Optional.of(c));return c;}
  @Test void batchesReadsPreservesOrderAndMissingMetricsRemainNull(){
    var c=campaign();var a=Creator.builder().id(UUID.randomUUID()).sourceType(CreatorSourceType.PUBLIC).build();
    var b=Creator.builder().id(UUID.randomUUID()).sourceType(CreatorSourceType.AUTHORIZED).owner(null).build();
    var ids=List.of(b.getId(),a.getId());
    when(shortlist.findByCampaignIdAndCreatorIdIn(c.getId(),ids)).thenReturn(List.of(ShortlistItem.builder().creator(a).note("A").build(),ShortlistItem.builder().creator(b).note("B").build()));
    var result=service.compare(c.getId(),new Request(ids));
    assertThat(result.creators()).extracting(i->i.creatorId()).containsExactly(b.getId(),a.getId());
    assertThat(result.creators()).allSatisfy(i->{assertThat(i.metrics()).isNull();assertThat(i.verifiedByInfluencerMatch()).isFalse();});
    verify(metrics,times(1)).latestForCreators(ids);
    verify(shortlist,never()).save(any());verify(campaigns,never()).save(any());
  }
  @Test void foreignCampaignIsHiddenBeforeReadingMembership(){
    var c=campaign();c.getBrandProfile().setUser(TestActors.user(Role.BRAND));
    assertThatThrownBy(()->service.compare(c.getId(),new Request(List.of(UUID.randomUUID(),UUID.randomUUID())))).isInstanceOf(NotFoundException.class);
    verifyNoInteractions(shortlist,metrics);
  }
  @Test void duplicateAndMissingMembersNeverReadMetrics(){
    var c=campaign();var id=UUID.randomUUID();
    assertThatThrownBy(()->service.compare(c.getId(),new Request(List.of(id,id)))).isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
    verifyNoInteractions(shortlist);
    assertThatThrownBy(()->service.compare(c.getId(),new Request(List.of(id,UUID.randomUUID())))).isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
    verifyNoInteractions(metrics);verify(shortlist,never()).save(any());
  }
  @Test void invalidSelectionFailsBeforeAnyRepositoryAccess(){
    assertThatThrownBy(()->service.compare(UUID.randomUUID(),new Request(List.of()))).isInstanceOf(ConstraintViolationException.class);
    verifyNoInteractions(campaigns,shortlist,metrics);
  }
}
