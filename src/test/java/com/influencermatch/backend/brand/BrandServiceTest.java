package com.influencermatch.backend.brand;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.dto.*;
import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.repository.*;
import com.influencermatch.backend.brand.service.BrandService;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.repository.UserRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;

class BrandServiceTest {
  final BrandProfileRepository profiles=mock(BrandProfileRepository.class);
  final BrandContextRepository contexts=mock(BrandContextRepository.class);
  final UserRepository users=mock(UserRepository.class);
  final ObjectMapper mapper=new ObjectMapper();
  final BrandService service=new BrandService(profiles,contexts,users,mapper);
  final com.influencermatch.backend.user.model.User owner=TestActors.user(Role.BRAND);
  final org.springframework.security.core.Authentication auth=TestActors.authenticate(owner);
  BrandRequest request(UUID userId) {
    return new BrandRequest(userId,"  Acme  ","Beauty",List.of(" Skin ","Skin",""),null,null,
        List.of(" VN ","VN"),null,null,List.of(Platform.TIKTOK,Platform.TIKTOK),null);
  }
  BrandProfile profile() { return BrandProfile.builder().id(UUID.randomUUID()).user(owner).businessName("Old").build(); }
  BrandContext context(BrandProfile p) {
    return BrandContext.builder().brandProfile(p).contextVersion(1).profileContext(mapper.createObjectNode())
        .learnedPatterns(mapper.createArrayNode()).evidenceRefs(mapper.createArrayNode()).build();
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  @Test void createNormalizesListsAndCreatesInitialContext() {
    when(users.findById(owner.getId())).thenReturn(Optional.of(owner));
    when(profiles.save(any())).thenAnswer(i->{BrandProfile p=i.getArgument(0);p.setId(UUID.randomUUID());return p;});
    when(contexts.save(any())).thenAnswer(i->i.getArgument(0));
    var result=service.create(request(null),auth);
    assertThat(result.businessName()).isEqualTo("Acme");assertThat(result.productCategories()).containsExactly("Skin");
    assertThat(result.preferredPlatforms()).containsExactly(Platform.TIKTOK);assertThat(result.m4Version()).isEqualTo(1);
    var saved=ArgumentCaptor.forClass(BrandContext.class);verify(contexts).save(saved.capture());
    assertThat(saved.getValue().getProfileContext().path("businessName").asText()).isEqualTo("Acme");
    assertThat(saved.getValue().getLearnedPatterns().isArray()).isTrue();
  }
  @Test void duplicateProfileDoesNotWriteContext() {
    when(users.findById(owner.getId())).thenReturn(Optional.of(owner));when(profiles.existsByUserId(owner.getId())).thenReturn(true);
    assertThatThrownBy(()->service.create(request(null),auth)).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.code()).isEqualTo(ErrorCode.BRAND_ALREADY_EXISTS));
    verify(profiles,never()).save(any());verifyNoInteractions(contexts);
  }
  @Test void cannotCreateForAnotherUser() {
    assertThatThrownBy(()->service.create(request(UUID.randomUUID()),auth)).isInstanceOf(ForbiddenException.class);
    verify(profiles,never()).save(any());verifyNoInteractions(contexts);
  }
  @Test void updateRefreshesContextAndPreservesLearnedEvidence() {
    var p=profile();var c=context(p);c.setLearnedPatterns(mapper.createArrayNode().add("learned"));
    c.setEvidenceRefs(mapper.createArrayNode().add("evidence"));
    when(profiles.findById(p.getId())).thenReturn(Optional.of(p));when(contexts.findByBrandProfileId(p.getId())).thenReturn(Optional.of(c));
    var result=service.update(p.getId(),request(null),auth);
    assertThat(result.m4Version()).isEqualTo(2);assertThat(c.getProfileContext().path("businessName").asText()).isEqualTo("Acme");
    assertThat(c.getLearnedPatterns().get(0).asText()).isEqualTo("learned");assertThat(c.getEvidenceRefs().get(0).asText()).isEqualTo("evidence");
  }
  @Test void updateMeFindsProfileByUserAndUpdates() {
    var p=profile();var c=context(p);
    when(profiles.findByUserId(owner.getId())).thenReturn(Optional.of(p));
    when(profiles.findById(p.getId())).thenReturn(Optional.of(p));
    when(contexts.findByBrandProfileId(p.getId())).thenReturn(Optional.of(c));
    var result=service.updateMe(request(null),auth);
    assertThat(result.m4Version()).isEqualTo(2);
    assertThat(p.getBusinessName()).isEqualTo("Acme");
  }
  @Test void foreignBrandCannotReadOrUpdate() {
    var p=profile();p.setUser(TestActors.user(Role.BRAND));when(profiles.findById(p.getId())).thenReturn(Optional.of(p));
    assertThatThrownBy(()->service.get(p.getId(),auth)).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(()->service.update(p.getId(),request(null),auth)).isInstanceOf(ForbiddenException.class);
    assertThat(p.getBusinessName()).isEqualTo("Old");verifyNoInteractions(contexts);
  }
  @Test void missingProfileReturnsSpecificError() {
    assertThatThrownBy(()->service.get(UUID.randomUUID(),auth)).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.code()).isEqualTo(ErrorCode.BRAND_NOT_FOUND));
  }
  @Test void laterListPageContainsNoForeignRows() {
    when(profiles.existsByUserId(owner.getId())).thenReturn(true);
    var page=service.list(PageRequest.of(1,1),auth);
    assertThat(page.items()).isEmpty();assertThat(page.totalElements()).isEqualTo(1);
    verify(profiles,never()).findAll(any(org.springframework.data.domain.Pageable.class));
  }
}
