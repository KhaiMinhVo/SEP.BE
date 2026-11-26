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
  final CampaignRepository campaigns = mock(CampaignRepository.class);
  final CampaignContextRepository contexts = mock(CampaignContextRepository.class);
  final BrandService brands = mock(BrandService.class);
  final com.influencermatch.backend.billing.service.SubscriptionService subscriptions =
      mock(com.influencermatch.backend.billing.service.SubscriptionService.class);
  final CampaignService service =
      new CampaignService(
          campaigns,
          contexts,
          brands,
          subscriptions,
          new ObjectMapper(),
          mock(PublicCreatorMetricRepository.class),
          mock(PostRepository.class));
  final com.influencermatch.backend.user.model.User user = TestActors.user(Role.BRAND);
  final org.springframework.security.core.Authentication auth = TestActors.authenticate(user);
  final BrandProfile brand = BrandProfile.builder().id(UUID.randomUUID()).user(user).build();

  @BeforeEach
  void wireM4() {
    com.influencermatch.backend.support.TestM4Fixtures.wire(service);
    var locks = mock(com.influencermatch.backend.campaign.service.CampaignWriteLock.class);
    lenient()
        .when(locks.owned(any()))
        .thenAnswer(
            i -> {
              var c =
                  campaigns
                      .findById(i.getArgument(0))
                      .orElseThrow(
                          () -> new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND, "Missing"));
              brands.requireAccessible(c.getBrandProfile().getId(), auth);
              return c;
            });
    var embeddings =
        mock(com.influencermatch.backend.campaign.embedding.CampaignEmbeddingStore.class);
    var brandContexts =
        mock(com.influencermatch.backend.brand.repository.BrandContextRepository.class);
    var mapper = new ObjectMapper().findAndRegisterModules();
    var lifecycle =
        new com.influencermatch.backend.campaign.service.CampaignM3ContextService(
            contexts, brandContexts, locks, embeddings, mapper, java.time.Clock.systemUTC());
    lenient()
        .when(contexts.saveAndFlush(any()))
        .thenAnswer(
            i -> {
              var c = (CampaignContext) i.getArgument(0);
              c.setId(UUID.randomUUID());
              c.setEmbeddingSourceContextId(c.getId());
              return c;
            });
    org.springframework.test.util.ReflectionTestUtils.setField(service, "writeLocks", locks);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "m3", lifecycle);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "embeddings", embeddings);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", mapper);
  }

  @Test
  void discoveryDelegatesFiltersToSharedEngine() {
    var campaign = Campaign.builder().id(UUID.randomUUID()).brandProfile(brand)
        .platforms(List.of("TIKTOK")).niches(List.of("BEAUTY")).build();
    when(campaigns.findById(campaign.getId())).thenReturn(Optional.of(campaign));
    var engine = mock(com.influencermatch.backend.discovery.service.CreatorDiscoveryService.class);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "discovery", engine);
    when(engine.discoverCreators(eq(campaign.getId()), any()))
        .thenReturn(org.springframework.data.domain.Page.empty());
    var filter = new com.influencermatch.backend.creator.dto.CreatorDiscoveryFilterRequest();
    filter.setKeyword("sunscreen"); filter.setNiche("BEAUTY"); filter.setIsClaimed(true);
    service.discoverCreators(campaign.getId(), filter,
        org.springframework.data.domain.PageRequest.of(0, 20), auth);
    var request = ArgumentCaptor.forClass(com.influencermatch.backend.discovery.dto.DiscoverySearchRequest.class);
    verify(engine).discoverCreators(eq(campaign.getId()), request.capture());
    assertThat(request.getValue().getKeyword()).isEqualTo("sunscreen");
    assertThat(request.getValue().getNiche()).isEqualTo("BEAUTY");
    assertThat(request.getValue().getIsClaimed()).isTrue();
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  CampaignRequest request(String invalid) {
    var start = LocalDate.of(2026, 1, 1);
    var end = invalid.equals("dates") ? start.minusDays(1) : start;
    return new CampaignRequest(
        " Name ",
        " Product ",
        CampaignObjective.AWARENESS,
        List.of(" VN ", "VN"),
        null,
        start,
        end,
        List.of(Platform.TIKTOK, Platform.TIKTOK),
        List.of(" Beauty ", "Beauty"),
        null,
        10L,
        invalid.equals("followers") ? 9L : 10L,
        null,
        null,
        "A brief",
        CreatorMode.SINGLE,
        1,
        List.of(new CampaignDeliverable(Platform.TIKTOK, "Review", 1)),
        BigDecimal.TEN,
        "VND",
        BigDecimal.TEN,
        invalid.equals("budget") ? BigDecimal.ONE : BigDecimal.TEN);
  }

  Campaign campaign(CampaignStatus status) {
    var c =
        Campaign.builder()
            .id(UUID.randomUUID())
            .brandProfile(brand)
            .name("Old")
            .niches(List.of("Beauty"))
            .productService("Product")
            .objective(CampaignObjective.AWARENESS)
            .status(status)
            .platforms(List.of("TIKTOK"))
            .brief("Brief")
            .creatorMode(CreatorMode.SINGLE)
            .targetCreatorCount(1)
            .totalBudget(BigDecimal.TEN)
            .startDate(LocalDate.of(2026, 1, 1))
            .endDate(LocalDate.of(2026, 1, 2))
            .deliverables(List.of(new CampaignDeliverable(Platform.TIKTOK, "Review", 1)))
            .build();
    when(campaigns.findById(c.getId())).thenReturn(Optional.of(c));
    return c;
  }

  @Test
  void createsDraftWithNormalizedListsAndNoPrematureContext() {
    when(brands.requireAccessible(brand.getId(), auth)).thenReturn(brand);
    when(campaigns.save(any())).thenAnswer(i -> i.getArgument(0));
    var result = service.create(brand.getId(), request("valid"), auth);
    assertThat(result.status()).isEqualTo(CampaignStatus.DRAFT);
    assertThat(result.name()).isEqualTo("Name");
    assertThat(result.platforms()).containsExactly(Platform.TIKTOK);
    assertThat(result.niches()).containsExactly("Beauty");
    verify(contexts, never()).save(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"dates", "followers", "budget"})
  void invalidRangesDoNotPersist(String field) {
    when(brands.requireAccessible(brand.getId(), auth)).thenReturn(brand);
    assertThatThrownBy(() -> service.create(brand.getId(), request(field), auth))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CAMPAIGN_RANGE));
    verifyNoInteractions(campaigns, contexts);
  }

  @ParameterizedTest
  @EnumSource(
      value = CampaignStatus.class,
      names = {"READY_FOR_DISCOVERY", "ACTIVE", "COMPLETED", "ARCHIVED"})
  void onlyDraftCanBeEdited(CampaignStatus status) {
    var c = campaign(status);
    assertThatThrownBy(
            () ->
                service.update(
                    c.getId(),
                    request("valid"),
                    auth,
                    com.influencermatch.backend.campaign.service.CampaignRevision.tag(
                        c.getId(), c.getCampaignRevision())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.CAMPAIGN_NOT_EDITABLE));
    assertThat(c.getName()).isEqualTo("Old");
    verify(contexts, never()).save(any());
  }

  @Test
  @DisplayName("changeStatus UTCID01 [N]: READY_FOR_DISCOVERY to ACTIVE increments revision")
  void readyTransitionCreatesOneContextAndSequentialActivationDoesNotRecreateIt() {
    var c = campaign(CampaignStatus.DRAFT);
    service.changeStatus(
        c.getId(),
        new CampaignStatusRequest(CampaignStatus.READY_FOR_DISCOVERY),
        auth,
        com.influencermatch.backend.campaign.service.CampaignRevision.tag(
            c.getId(), c.getCampaignRevision()));
    var saved = ArgumentCaptor.forClass(CampaignContext.class);
    verify(contexts).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getContextVersion()).isEqualTo(1);
    assertThat(saved.getValue().getContextData().path("objective").asText()).isEqualTo("AWARENESS");
    long revision = c.getCampaignRevision();
    service.changeStatus(
        c.getId(),
        new CampaignStatusRequest(CampaignStatus.ACTIVE),
        auth,
        com.influencermatch.backend.campaign.service.CampaignRevision.tag(
            c.getId(), c.getCampaignRevision()));
    assertThat(c.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
    assertThat(c.getCampaignRevision()).isEqualTo(revision + 1);
    verify(contexts, times(1)).saveAndFlush(any());
  }

  @Test
  void missingCategoryBlocksReadyAndRecommendationPreparation() {
    var c = campaign(CampaignStatus.DRAFT);
    c.setNiches(List.of());
    String revision = com.influencermatch.backend.campaign.service.CampaignRevision.tag(c.getId(), c.getCampaignRevision());
    assertThatThrownBy(() -> service.changeStatus(c.getId(), new CampaignStatusRequest(CampaignStatus.READY_FOR_DISCOVERY), auth, revision))
        .isInstanceOfSatisfying(com.influencermatch.backend.exception.ProfileIncompleteException.class,
            e -> assertThat(e.fields()).containsKey("niches"));
    assertThatThrownBy(() -> service.prepareForRecommendation(c.getId(), revision))
        .isInstanceOfSatisfying(com.influencermatch.backend.exception.ProfileIncompleteException.class,
            e -> assertThat(e.fields()).containsKey("niches"));
    assertThat(c.getStatus()).isEqualTo(CampaignStatus.DRAFT);
    verify(contexts, never()).saveAndFlush(any());
  }

  @Test
  void missingPlatformsCannotCreateDiscoveryContext() {
    var c = campaign(CampaignStatus.DRAFT);
    c.setPlatforms(List.of());
    assertThatThrownBy(
            () ->
                service.changeStatus(
                    c.getId(),
                    new CampaignStatusRequest(CampaignStatus.READY_FOR_DISCOVERY),
                    auth,
                    com.influencermatch.backend.campaign.service.CampaignRevision.tag(
                        c.getId(), c.getCampaignRevision())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    assertThat(c.getStatus()).isEqualTo(CampaignStatus.DRAFT);
    verify(contexts, never()).save(any());
  }

  @Test
  void archiveIsIdempotentAndMissingCampaignIs404() {
    var c = campaign(CampaignStatus.ARCHIVED);
    assertThat(
            service
                .archive(
                    c.getId(),
                    auth,
                    com.influencermatch.backend.campaign.service.CampaignRevision.tag(
                        c.getId(), c.getCampaignRevision()))
                .status())
        .isEqualTo(CampaignStatus.ARCHIVED);
    assertThatThrownBy(() -> service.get(UUID.randomUUID(), auth))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.CAMPAIGN_NOT_FOUND));
  }

  @Test
  void ownershipFailureHappensBeforeMutation() {
    var c = campaign(CampaignStatus.DRAFT);
    when(brands.requireAccessible(brand.getId(), auth))
        .thenThrow(new ForbiddenException("Not owner"));
    assertThatThrownBy(
            () ->
                service.update(
                    c.getId(),
                    request("valid"),
                    auth,
                    com.influencermatch.backend.campaign.service.CampaignRevision.tag(
                        c.getId(), c.getCampaignRevision())))
        .isInstanceOf(ForbiddenException.class);
    assertThat(c.getName()).isEqualTo("Old");
    assertThat(c.getStatus()).isEqualTo(CampaignStatus.DRAFT);
    verify(contexts, never()).save(any());
  }

  @Test
  @DisplayName("changeStatus UTCID02 [B]: ACTIVE to COMPLETED with 1 completed collab succeeds and increments revision")
  void changeStatus_activeToCompletedWithOneCompletedCollab() {
    var c = campaign(CampaignStatus.ACTIVE);
    long rev = c.getCampaignRevision();
    var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "executionDb", jdbc);
    when(jdbc.queryForObject(contains("status='COMPLETED'"), eq(Long.class), eq(c.getId()))).thenReturn(1L);
    when(jdbc.queryForObject(contains("not in ('COMPLETED'"), eq(Long.class), eq(c.getId()))).thenReturn(0L);
    when(jdbc.queryForObject(contains("cooperation_deal"), eq(Long.class), eq(c.getId()))).thenReturn(0L);

    var res = service.changeStatus(
        c.getId(),
        new CampaignStatusRequest(CampaignStatus.COMPLETED),
        auth,
        com.influencermatch.backend.campaign.service.CampaignRevision.tag(c.getId(), rev));

    assertThat(res.status()).isEqualTo(CampaignStatus.COMPLETED);
    assertThat(c.getCampaignRevision()).isEqualTo(rev + 1);
  }

  @Test
  @DisplayName("changeStatus UTCID03 [A]: ACTIVE to COMPLETED with 0 completed collabs throws INVALID_COLLABORATION_STATE")
  void changeStatus_activeToCompletedWithZeroCompletedThrows() {
    var c = campaign(CampaignStatus.ACTIVE);
    long rev = c.getCampaignRevision();
    var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "executionDb", jdbc);
    when(jdbc.queryForObject(contains("status='COMPLETED'"), eq(Long.class), eq(c.getId()))).thenReturn(0L);
    when(jdbc.queryForObject(contains("not in ('COMPLETED'"), eq(Long.class), eq(c.getId()))).thenReturn(0L);
    when(jdbc.queryForObject(contains("cooperation_deal"), eq(Long.class), eq(c.getId()))).thenReturn(0L);

    assertThatThrownBy(() -> service.changeStatus(
        c.getId(),
        new CampaignStatusRequest(CampaignStatus.COMPLETED),
        auth,
        com.influencermatch.backend.campaign.service.CampaignRevision.tag(c.getId(), rev)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_COLLABORATION_STATE));
    assertThat(c.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
  }

  @Test
  @DisplayName("changeStatus UTCID04 [A]: DRAFT to ACTIVE throws INVALID_CAMPAIGN_TRANSITION")
  void changeStatus_draftToActiveRejected() {
    var c = campaign(CampaignStatus.DRAFT);
    String rev = com.influencermatch.backend.campaign.service.CampaignRevision.tag(c.getId(), c.getCampaignRevision());

    assertThatThrownBy(() -> service.changeStatus(
        c.getId(), new CampaignStatusRequest(CampaignStatus.ACTIVE), auth, rev))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_CAMPAIGN_TRANSITION));
    assertThat(c.getStatus()).isEqualTo(CampaignStatus.DRAFT);
  }

  @Test
  @DisplayName("changeStatus UTCID05 [A]: ACTIVE to CLOSED with blank reason throws ValidationException")
  void changeStatus_activeToClosedWithBlankReasonThrowsValidation() {
    var c = campaign(CampaignStatus.ACTIVE);
    long rev = c.getCampaignRevision();
    var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "executionDb", jdbc);
    when(jdbc.queryForObject(contains("status='COMPLETED'"), eq(Long.class), eq(c.getId()))).thenReturn(0L);
    when(jdbc.queryForObject(contains("not in ('COMPLETED'"), eq(Long.class), eq(c.getId()))).thenReturn(0L);
    when(jdbc.queryForObject(contains("cooperation_deal"), eq(Long.class), eq(c.getId()))).thenReturn(0L);

    assertThatThrownBy(() -> service.changeStatus(
        c.getId(),
        new CampaignStatusRequest(CampaignStatus.CLOSED, "   "),
        auth,
        com.influencermatch.backend.campaign.service.CampaignRevision.tag(c.getId(), rev)))
        .isInstanceOf(ValidationException.class);
    assertThat(c.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
  }

  @Test
  @DisplayName("archiveCampaign UTCID01 [N]: DRAFT campaign archived and revision incremented")
  void archive_draftCampaignSucceedsAndIncrementsRevision() {
    var c = campaign(CampaignStatus.DRAFT);
    long rev = c.getCampaignRevision();

    var res = service.archive(
        c.getId(),
        auth,
        com.influencermatch.backend.campaign.service.CampaignRevision.tag(c.getId(), rev));

    assertThat(res.status()).isEqualTo(CampaignStatus.ARCHIVED);
    assertThat(c.getCampaignRevision()).isEqualTo(rev + 1);
  }

  @Test
  @DisplayName("archiveCampaign UTCID03 [A]: ACTIVE campaign cannot be archived (INVALID_CAMPAIGN_TRANSITION)")
  void archive_activeCampaignRejected() {
    var c = campaign(CampaignStatus.ACTIVE);
    String rev = com.influencermatch.backend.campaign.service.CampaignRevision.tag(c.getId(), c.getCampaignRevision());

    assertThatThrownBy(() -> service.archive(c.getId(), auth, rev))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_CAMPAIGN_TRANSITION));
    assertThat(c.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
  }

  @Test
  @DisplayName("archiveCampaign UTCID04 [A]: Stale revision header throws CAMPAIGN_REVISION_CONFLICT")
  void archive_staleRevisionThrowsConflict() {
    var c = campaign(CampaignStatus.DRAFT);
    String staleRev = com.influencermatch.backend.campaign.service.CampaignRevision.tag(
        c.getId(), c.getCampaignRevision() + 99);

    assertThatThrownBy(() -> service.archive(c.getId(), auth, staleRev))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CAMPAIGN_REVISION_CONFLICT));
    assertThat(c.getStatus()).isEqualTo(CampaignStatus.DRAFT);
  }

  @Test
  @DisplayName("archiveCampaign UTCID05 [A]: Missing revision header throws PRECONDITION_REQUIRED")
  void archive_missingRevisionThrowsPreconditionRequired() {
    var c = campaign(CampaignStatus.DRAFT);

    assertThatThrownBy(() -> service.archive(c.getId(), auth, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.PRECONDITION_REQUIRED));
    assertThat(c.getStatus()).isEqualTo(CampaignStatus.DRAFT);
  }
}
