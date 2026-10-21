package com.influencermatch.backend.campaign;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.service.BrandService;
import com.influencermatch.backend.campaign.dto.*;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.model.*;
import com.influencermatch.backend.campaign.repository.*;
import com.influencermatch.backend.campaign.service.CampaignService;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

class CampaignServiceTest {
  final CampaignRepository campaigns=mock(CampaignRepository.class);
  final CampaignContextRepository contexts=mock(CampaignContextRepository.class);
  final BrandService brands=mock(BrandService.class);
  final CampaignService service=new CampaignService(campaigns,contexts,brands,new ObjectMapper(),
      mock(PublicCreatorMetricRepository.class),mock(PostRepository.class));
  final com.influencermatch.backend.user.model.User user=TestActors.user(Role.BRAND);
  final org.springframework.security.core.Authentication auth=TestActors.authenticate(user);
  final BrandProfile brand=BrandProfile.builder().id(UUID.randomUUID()).user(user).build();
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  CampaignRequest request(String invalid) {
    var start=LocalDate.of(2026,1,1);var end=invalid.equals("dates")?start.minusDays(1):start;
    return new CampaignRequest(" Name "," Product ",CampaignObjective.AWARENESS,List.of(" VN ","VN"),null,start,end,
        List.of(Platform.TIKTOK,Platform.TIKTOK),List.of(" Beauty ","Beauty"),null,
        10L,invalid.equals("followers")?9L:10L,BigDecimal.TEN,invalid.equals("budget")?BigDecimal.ONE:BigDecimal.TEN);
  }
  Campaign campaign(CampaignStatus status) {
    var c=Campaign.builder().id(UUID.randomUUID()).brandProfile(brand).name("Old").productService("Product")
        .objective(CampaignObjective.AWARENESS).status(status).platforms(List.of("TIKTOK")).build();
    when(campaigns.findById(c.getId())).thenReturn(Optional.of(c));return c;
  }
  @Test void createsDraftWithNormalizedListsAndNoPrematureContext() {
    when(brands.requireAccessible(brand.getId(),auth)).thenReturn(brand);
    when(campaigns.save(any())).thenAnswer(i->i.getArgument(0));
    var result=service.create(brand.getId(),request("valid"),auth);
    assertThat(result.status()).isEqualTo(CampaignStatus.DRAFT);assertThat(result.name()).isEqualTo("Name");
    assertThat(result.platforms()).containsExactly(Platform.TIKTOK);assertThat(result.niches()).containsExactly("Beauty");
    verifyNoInteractions(contexts);
  }
  @ParameterizedTest @ValueSource(strings={"dates","followers","budget"})
  void invalidRangesDoNotPersist(String field) {
    when(brands.requireAccessible(brand.getId(),auth)).thenReturn(brand);
    assertThatThrownBy(()->service.create(brand.getId(),request(field),auth)).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CAMPAIGN_RANGE));
    verifyNoInteractions(campaigns,contexts);
  }
  @ParameterizedTest @EnumSource(value=CampaignStatus.class,names={"READY_FOR_DISCOVERY","ACTIVE","COMPLETED","ARCHIVED"})
  void onlyDraftCanBeEdited(CampaignStatus status) {
    var c=campaign(status);
    assertThatThrownBy(()->service.update(c.getId(),request("valid"),auth)).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.code()).isEqualTo(ErrorCode.CAMPAIGN_NOT_EDITABLE));
    assertThat(c.getName()).isEqualTo("Old");verifyNoInteractions(contexts);
  }
  @Test void readyTransitionCreatesOneContextAndSequentialActivationDoesNotRecreateIt() {
    var c=campaign(CampaignStatus.DRAFT);
    service.changeStatus(c.getId(),new CampaignStatusRequest(CampaignStatus.READY_FOR_DISCOVERY),auth);
    var saved=ArgumentCaptor.forClass(CampaignContext.class);verify(contexts).save(saved.capture());
    assertThat(saved.getValue().getContextVersion()).isEqualTo(1);
    assertThat(saved.getValue().getContextData().path("objective").asText()).isEqualTo("AWARENESS");
    service.changeStatus(c.getId(),new CampaignStatusRequest(CampaignStatus.ACTIVE),auth);
    assertThat(c.getStatus()).isEqualTo(CampaignStatus.ACTIVE);verify(contexts,times(1)).save(any());
  }
  @Test void missingPlatformsCannotCreateDiscoveryContext() {
    var c=campaign(CampaignStatus.DRAFT);c.setPlatforms(List.of());
    assertThatThrownBy(()->service.changeStatus(c.getId(),new CampaignStatusRequest(CampaignStatus.READY_FOR_DISCOVERY),auth))
        .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.CAMPAIGN_CONTEXT_NOT_READY));
    assertThat(c.getStatus()).isEqualTo(CampaignStatus.DRAFT);verifyNoInteractions(contexts);
  }
  @Test void archiveIsIdempotentAndMissingCampaignIs404() {
    var c=campaign(CampaignStatus.ARCHIVED);
    assertThat(service.archive(c.getId(),auth).status()).isEqualTo(CampaignStatus.ARCHIVED);
    assertThatThrownBy(()->service.get(UUID.randomUUID(),auth)).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.code()).isEqualTo(ErrorCode.CAMPAIGN_NOT_FOUND));
  }
  @Test void ownershipFailureHappensBeforeMutation() {
    var c=campaign(CampaignStatus.DRAFT);
    when(brands.requireAccessible(brand.getId(),auth)).thenThrow(new ForbiddenException("Not owner"));
    assertThatThrownBy(()->service.update(c.getId(),request("valid"),auth)).isInstanceOf(ForbiddenException.class);
    assertThat(c.getName()).isEqualTo("Old");assertThat(c.getStatus()).isEqualTo(CampaignStatus.DRAFT);
    verifyNoInteractions(contexts);
  }
}
