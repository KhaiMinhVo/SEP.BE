package com.influencermatch.backend.collaboration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.collaboration.dto.*;
import com.influencermatch.backend.collaboration.enums.*;
import com.influencermatch.backend.collaboration.model.*;
import com.influencermatch.backend.collaboration.repository.*;
import com.influencermatch.backend.collaboration.service.CollaborationService;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.relationship.enums.RelationshipStage;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CollaborationServiceTest {
  CampaignRepository campaigns = mock(CampaignRepository.class);
  BrandCreatorRelationshipRepository relationships = mock(BrandCreatorRelationshipRepository.class);
  CollaborationRepository collaborations = mock(CollaborationRepository.class);
  CampaignOutcomeRepository outcomes = mock(CampaignOutcomeRepository.class);
  CreatorReviewRepository reviews = mock(CreatorReviewRepository.class);
  EntityManager em = mock(EntityManager.class);
  jakarta.validation.ValidatorFactory factory;
  CollaborationService service;
  User owner;
  Campaign campaign;
  BrandCreatorRelationship relationship;
  Collaboration collaboration;

  @BeforeEach
  void setup() {
    factory = Validation.buildDefaultValidatorFactory();
    service =
        new CollaborationService(
            campaigns,
            relationships,
            collaborations,
            outcomes,
            reviews,
            em,
            factory.getValidator());
    owner = User.builder().id(UUID.randomUUID()).role(Role.BRAND).status(UserStatus.ACTIVE).build();
    authenticate(owner);
    campaign =
        Campaign.builder()
            .id(UUID.randomUUID())
            .status(CampaignStatus.ACTIVE)
            .brandProfile(BrandProfile.builder().user(owner).build())
            .build();
    Creator creator = Creator.builder().id(UUID.randomUUID()).build();
    relationship =
        BrandCreatorRelationship.builder()
            .id(UUID.randomUUID())
            .user(owner)
            .campaign(campaign)
            .creator(creator)
            .stage(RelationshipStage.CONTACTED)
            .build();
    collaboration =
        Collaboration.builder()
            .id(UUID.randomUUID())
            .campaign(campaign)
            .creator(creator)
            .relationship(relationship)
            .status(CollaborationStatus.IN_PROGRESS)
            .paymentStatus(CollaborationPaymentStatus.PAID)
            .build();
    when(campaigns.findLockedForCollaborationById(campaign.getId()))
        .thenReturn(Optional.of(campaign));
    when(relationships.findLockedById(relationship.getId())).thenReturn(Optional.of(relationship));
    when(collaborations.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
    when(collaborations.findLockedById(collaboration.getId()))
        .thenReturn(Optional.of(collaboration));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    factory.close();
  }

  void authenticate(User user) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
  }

  CreateCollaborationRequest request() {
    return new CreateCollaborationRequest(
        relationship.getId(), new BigDecimal("1200000.50"), " vnd ", " note ");
  }

  RecordOutcomeRequest outcome() {
    return new RecordOutcomeRequest(1000L, 20L, " https://example.com/post ", " result ");
  }

  CreateCreatorReviewRequest review() {
    return new CreateCreatorReviewRequest(5, 4, false, " review ");
  }

  @Test
  void createsAndSynchronizesFeeAndStage() {
    when(collaborations.save(any()))
        .thenAnswer(
            i -> {
              Collaboration c = i.getArgument(0);
              c.setId(UUID.randomUUID());
              return c;
            });
    var response = service.create(campaign.getId(), request());
    assertThat(response.currency()).isEqualTo("VND");
    assertThat(response.status()).isEqualTo(CollaborationStatus.IN_PROGRESS);
    assertThat(response.paymentStatus()).isEqualTo(CollaborationPaymentStatus.PENDING);
    assertThat(response.startAt()).isNotNull();
    assertThat(response.note()).isEqualTo("note");
    assertThat(relationship.getStage()).isEqualTo(RelationshipStage.COLLABORATING);
    assertThat(relationship.getAgreedFee()).isEqualByComparingTo("1200000.50");
    verify(em).refresh(relationship);
  }

  @Test
  void outcomeCompletesOnlyCollaborationAndPreservesPayment() {
    when(outcomes.save(any()))
        .thenAnswer(
            i -> {
              CampaignOutcome o = i.getArgument(0);
              o.setId(UUID.randomUUID());
              return o;
            });
    var response = service.recordOutcome(collaboration.getId(), outcome());
    assertThat(response.views()).isEqualTo(1000L);
    assertThat(response.clicks()).isEqualTo(20L);
    assertThat(response.source()).isEqualTo("SELF_REPORTED");
    assertThat(collaboration.getPublishedContentUrl()).isEqualTo("https://example.com/post");
    assertThat(collaboration.getStatus()).isEqualTo(CollaborationStatus.COMPLETED);
    assertThat(relationship.getStage()).isEqualTo(RelationshipStage.COMPLETED);
    assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
    assertThat(collaboration.getPaymentStatus()).isEqualTo(CollaborationPaymentStatus.PAID);
  }

  @Test
  void absentMetricStaysNullAndZeroIsValid() {
    when(outcomes.save(any()))
        .thenAnswer(
            i -> {
              CampaignOutcome o = i.getArgument(0);
              o.setId(UUID.randomUUID());
              return o;
            });
    var response =
        service.recordOutcome(
            collaboration.getId(),
            new RecordOutcomeRequest(null, 0L, "https://example.com/post", null));
    assertThat(response.views()).isNull();
    assertThat(response.clicks()).isZero();
  }

  @Test
  void reviewUsesAuthenticatedOwnerAndActualOutcome() {
    campaign.setStatus(CampaignStatus.COMPLETED);
    collaboration.setStatus(CollaborationStatus.COMPLETED);
    var stored =
        CampaignOutcome.builder().id(UUID.randomUUID()).collaboration(collaboration).build();
    when(outcomes.findByCollaborationId(collaboration.getId())).thenReturn(Optional.of(stored));
    when(reviews.save(any()))
        .thenAnswer(
            i -> {
              CreatorReview r = i.getArgument(0);
              r.setId(UUID.randomUUID());
              return r;
            });
    var result = service.review(collaboration.getId(), review());
    assertThat(result.reviewedByUserId()).isEqualTo(owner.getId());
    assertThat(result.outcomeId()).isEqualTo(stored.getId());
    assertThat(result.wouldCollaborateAgain()).isFalse();
    assertThat(result.note()).isEqualTo("review");
  }

  @Test
  void duplicateOperationsHaveSpecificConflictCodes() {
    when(collaborations.existsByRelationshipId(relationship.getId())).thenReturn(true);
    assertCode(
        () -> service.create(campaign.getId(), request()), ErrorCode.COLLABORATION_ALREADY_EXISTS);
    when(outcomes.existsByCollaborationId(collaboration.getId())).thenReturn(true);
    assertCode(
        () -> service.recordOutcome(collaboration.getId(), outcome()),
        ErrorCode.OUTCOME_ALREADY_EXISTS);
    collaboration.setStatus(CollaborationStatus.COMPLETED);
    when(outcomes.findByCollaborationId(collaboration.getId()))
        .thenReturn(Optional.of(CampaignOutcome.builder().build()));
    when(reviews.existsByCollaborationId(collaboration.getId())).thenReturn(true);
    assertCode(
        () -> service.review(collaboration.getId(), review()),
        ErrorCode.CREATOR_REVIEW_ALREADY_EXISTS);
  }

  @Test
  void reviewRequiresCompletedCollaborationAndOutcome() {
    assertCode(
        () -> service.review(collaboration.getId(), review()),
        ErrorCode.INVALID_COLLABORATION_STATE);
    collaboration.setStatus(CollaborationStatus.COMPLETED);
    assertCode(
        () -> service.review(collaboration.getId(), review()),
        ErrorCode.INVALID_COLLABORATION_STATE);
  }

  @ParameterizedTest
  @EnumSource(
      value = CampaignStatus.class,
      names = {"DRAFT", "READY_FOR_DISCOVERY", "COMPLETED", "ARCHIVED"})
  void createAndOutcomeRequireActiveCampaign(CampaignStatus status) {
    campaign.setStatus(status);
    assertCode(
        () -> service.create(campaign.getId(), request()), ErrorCode.INVALID_COLLABORATION_STATE);
    assertCode(
        () -> service.recordOutcome(collaboration.getId(), outcome()),
        ErrorCode.INVALID_COLLABORATION_STATE);
  }

  @ParameterizedTest
  @EnumSource(
      value = RelationshipStage.class,
      names = {"SHORTLISTED", "COLLABORATING", "NOT_INTERESTED", "COMPLETED"})
  void createRejectsIneligibleRelationshipStages(RelationshipStage stage) {
    relationship.setStage(stage);
    assertCode(
        () -> service.create(campaign.getId(), request()), ErrorCode.INVALID_COLLABORATION_STATE);
  }

  @Test
  void crossCampaignAndInvalidCurrencyDoNotWrite() {
    relationship.setCampaign(Campaign.builder().id(UUID.randomUUID()).build());
    assertCode(() -> service.create(campaign.getId(), request()), ErrorCode.VALIDATION_ERROR);
    relationship.setCampaign(campaign);
    assertCode(
        () ->
            service.create(
                campaign.getId(),
                new CreateCollaborationRequest(relationship.getId(), BigDecimal.ZERO, "ZZZ", null)),
        ErrorCode.VALIDATION_ERROR);
    verify(collaborations, never()).save(any());
  }

  @Test
  void foreignCampaignAndRelationshipOwnerAreForbidden() {
    campaign.getBrandProfile().setUser(User.builder().id(UUID.randomUUID()).build());
    assertCode(() -> service.create(campaign.getId(), request()), ErrorCode.ACCESS_DENIED);
    campaign.getBrandProfile().setUser(owner);
    relationship.setUser(User.builder().id(UUID.randomUUID()).build());
    assertCode(() -> service.create(campaign.getId(), request()), ErrorCode.ACCESS_DENIED);
    verify(collaborations, never()).save(any());
  }

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"ADMIN", "DATA_MANAGER"})
  void serviceGuardRejectsInternalRoles(Role role) {
    owner.setRole(role);
    authenticate(owner);
    assertCode(() -> service.create(campaign.getId(), request()), ErrorCode.ACCESS_DENIED);
    assertCode(
        () -> service.recordOutcome(collaboration.getId(), outcome()), ErrorCode.ACCESS_DENIED);
    assertCode(() -> service.review(collaboration.getId(), review()), ErrorCode.ACCESS_DENIED);
    verifyNoInteractions(campaigns, collaborations, relationships, outcomes, reviews);
  }

  @Test
  void corruptRelationshipCannotBeUsedForOutcome() {
    relationship.setCreator(Creator.builder().id(UUID.randomUUID()).build());
    assertCode(
        () -> service.recordOutcome(collaboration.getId(), outcome()),
        ErrorCode.INVALID_COLLABORATION_STATE);
    verify(outcomes, never()).save(any());
  }

  void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(code));
  }
}
