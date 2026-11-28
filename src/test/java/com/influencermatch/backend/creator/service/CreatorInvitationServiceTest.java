package com.influencermatch.backend.creator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.creator.dto.InvitationResponse;
import com.influencermatch.backend.creator.dto.VerifyTokenResponse;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorInvitation;
import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.creator.repository.CreatorInvitationRepository;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CreatorInvitationServiceTest {

  private CreatorRepository creatorRepository;
  private CreatorInvitationRepository creatorInvitationRepository;
  private BrandProfileRepository brandProfileRepository;
  private CampaignRepository campaignRepository;
  private UserRepository userRepository;
  private CreatorInvitationService invitationService;

  private User mockBrandUser;

  @BeforeEach
  void setUp() {
    creatorRepository = mock(CreatorRepository.class);
    creatorInvitationRepository = mock(CreatorInvitationRepository.class);
    brandProfileRepository = mock(BrandProfileRepository.class);
    campaignRepository = mock(CampaignRepository.class);
    userRepository = mock(UserRepository.class);

    invitationService = com.influencermatch.backend.support.CreatorServiceFixtures.wire(new CreatorInvitationService(
        creatorRepository,
        creatorInvitationRepository,
        brandProfileRepository,
        campaignRepository,
        userRepository
    ));

    mockBrandUser = User.builder()
        .id(UUID.randomUUID())
        .email("brand@influencermatch.com")
        .fullName("Acme Corp")
        .role(Role.BRAND)
        .status(UserStatus.ACTIVE)
        .build();

    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(mockBrandUser, null, mockBrandUser.getAuthorities())
    );
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("sendCreatorInvitation UTCID01 [N]: Owned campaign invitation is queued with seven-day expiry")
  void sendInvitation_success() {
    UUID creatorId = UUID.randomUUID();
    UUID campaignId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId)
        .platform("YOUTUBE")
        .externalId("UC12345")
        .userName("tech_review")
        .sourceType(CreatorSourceType.PUBLIC)
        .build();

    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(creator));
    BrandProfile brandProfile = BrandProfile.builder()
        .id(UUID.randomUUID()).user(mockBrandUser).businessName("Acme").build();
    when(brandProfileRepository.findByUserId(mockBrandUser.getId())).thenReturn(Optional.of(brandProfile));
    var ownedCampaign = com.influencermatch.backend.campaign.model.Campaign.builder()
        .id(campaignId)
        .brandProfile(brandProfile)
        .build();
    when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(ownedCampaign));
    when(creatorInvitationRepository.save(any())).thenAnswer(i -> {
      CreatorInvitation inv = i.getArgument(0);
      inv.setId(UUID.randomUUID());
      return inv;
    });

    InvitationResponse response = invitationService.sendInvitation(
        creatorId, "koc@gmail.com", null, campaignId);

    assertThat(response.getCreatorId()).isEqualTo(creatorId);
    assertThat(response.getEmail()).isEqualTo("koc@gmail.com");
    assertThat(response.getStatus()).isEqualTo("PENDING");
    var mail=org.mockito.ArgumentCaptor.forClass(String.class);
    var outbox=(com.influencermatch.backend.creator.email.ClaimEmailOutboxService)org.springframework.test.util.ReflectionTestUtils.getField(invitationService,"outbox");
    verify(outbox).requireConfigured();verify(outbox).enqueue(any(),mail.capture());
    assertThat(response.getEmailStatus()).isEqualTo("QUEUED");
    String rawToken=java.net.URI.create(mail.getValue()).getQuery().substring("token=".length());
    assertThat(rawToken).hasSize(64);

    ArgumentCaptor<CreatorInvitation> captor = ArgumentCaptor.forClass(CreatorInvitation.class);
    verify(creatorInvitationRepository).save(captor.capture());
    CreatorInvitation saved = captor.getValue();
    assertThat(saved.getTargetCreator()).isEqualTo(creator);
    assertThat(saved.getCampaign()).isEqualTo(ownedCampaign);
    assertThat(saved.getTokenHash()).isEqualTo(CreatorInvitationService.hashToken(rawToken));
    assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(6));
  }

  @Test
  @DisplayName("sendCreatorInvitation UTCID04 [A]: Already-linked creator cannot be invited")
  void sendInvitation_alreadyClaimed_throwsConflict() {
    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId)
        .platform("YOUTUBE")
        .externalId("UC12345")
        .master(CreatorMaster.builder().id(UUID.randomUUID()).build())
        .sourceType(CreatorSourceType.AUTHORIZED)
        .build();

    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(creator));
    when(brandProfileRepository.findByUserId(mockBrandUser.getId())).thenReturn(Optional.of(BrandProfile.builder().id(UUID.randomUUID()).user(mockBrandUser).build()));

    assertThatThrownBy(() -> invitationService.sendInvitation(creatorId, "koc@gmail.com", null, null))
        .isInstanceOf(ConflictException.class)
        .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo(ErrorCode.CREATOR_ALREADY_LINKED));
  }

  @Test
  void verifyInvitationToken_success() {
    String rawToken = "my_secret_token_12345";
    String tokenHash = CreatorInvitationService.hashToken(rawToken);

    Creator creator = Creator.builder()
        .id(UUID.randomUUID())
        .platform("YOUTUBE")
        .userName("tech_review")
        .displayName("Tech Review VN")
        .avatarUrl("https://example.com/avatar.jpg")
        .build();

    CreatorInvitation invitation = CreatorInvitation.builder()
        .id(UUID.randomUUID())
        .targetCreator(creator)
        .tokenHash(tokenHash)
        .status("PENDING")
        .expiresAt(LocalDateTime.now().plusDays(3))
        .build();

    when(creatorInvitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));

    VerifyTokenResponse response = invitationService.verifyInvitationToken(rawToken);

    assertThat(response.getCreatorId()).isEqualTo(creator.getId());
    assertThat(response.getPlatform()).isEqualTo("YOUTUBE");
    assertThat(response.getUserName()).isEqualTo("tech_review");
    assertThat(response.getDisplayName()).isEqualTo("Tech Review VN");
  }

  @Test
  void verifyInvitationToken_expired_throwsConflict() {
    String rawToken = "expired_token";
    String tokenHash = CreatorInvitationService.hashToken(rawToken);

    CreatorInvitation invitation = CreatorInvitation.builder()
        .id(UUID.randomUUID())
        .tokenHash(tokenHash)
        .status("PENDING")
        .expiresAt(LocalDateTime.now().minusHours(1))
        .build();

    when(creatorInvitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));

    assertThatThrownBy(() -> invitationService.verifyInvitationToken(rawToken))
        .isInstanceOf(ConflictException.class)
        .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo(ErrorCode.INVITATION_EXPIRED));

    assertThat(invitation.getStatus()).isEqualTo("PENDING");
    verify(creatorInvitationRepository,never()).save(any());
  }

  @Test
  void verifyInvitationToken_alreadyProcessed_throwsConflict() {
    String rawToken = "processed_token";
    String tokenHash = CreatorInvitationService.hashToken(rawToken);

    CreatorInvitation invitation = CreatorInvitation.builder()
        .id(UUID.randomUUID())
        .tokenHash(tokenHash)
        .status("ACCEPTED")
        .expiresAt(LocalDateTime.now().plusDays(2))
        .build();

    when(creatorInvitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));

    assertThatThrownBy(() -> invitationService.verifyInvitationToken(rawToken))
        .isInstanceOf(ConflictException.class)
        .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo(ErrorCode.INVITATION_ALREADY_PROCESSED));
  }

  @Test
  void sendInvitation_previousExpiredAtNowPermittedWith7DayExpiry() {
    java.time.Instant fixedInstant = java.time.Instant.parse("2026-11-26T10:00:00Z");
    java.time.Clock fixedClock = java.time.Clock.fixed(fixedInstant, java.time.ZoneOffset.UTC);
    org.springframework.test.util.ReflectionTestUtils.setField(invitationService, "clock", fixedClock);
    LocalDateTime now = LocalDateTime.now(fixedClock);

    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId).platform("TIKTOK").externalId("tt_1").userName("koc_1")
        .sourceType(CreatorSourceType.PUBLIC).build();
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(creator));
    when(brandProfileRepository.findByUserId(mockBrandUser.getId())).thenReturn(Optional.of(
        BrandProfile.builder().id(UUID.randomUUID()).user(mockBrandUser).businessName("Acme").build()));
    when(creatorInvitationRepository.findFirstByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
            creatorId, "PENDING", now)).thenReturn(Optional.empty());
    when(creatorInvitationRepository.save(any())).thenAnswer(i -> {
      CreatorInvitation inv = i.getArgument(0);
      inv.setId(UUID.randomUUID());
      return inv;
    });

    InvitationResponse response = invitationService.sendInvitation(creatorId, "koc@gmail.com", null, null);

    assertThat(response.getExpiresAt()).isEqualTo(now.plusDays(7));
    assertThat(response.getEmailStatus()).isEqualTo("QUEUED");
  }

  @Test
  void sendInvitation_nullCampaignNormalizesEmail() {
    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId).platform("YOUTUBE").externalId("UC99").userName("koc_yt")
        .sourceType(CreatorSourceType.PUBLIC).build();
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(creator));
    when(brandProfileRepository.findByUserId(mockBrandUser.getId())).thenReturn(Optional.of(
        BrandProfile.builder().id(UUID.randomUUID()).user(mockBrandUser).businessName("Acme").build()));
    when(creatorInvitationRepository.save(any())).thenAnswer(i -> {
      CreatorInvitation inv = i.getArgument(0);
      inv.setId(UUID.randomUUID());
      return inv;
    });

    InvitationResponse response = invitationService.sendInvitation(creatorId, "  KOC.Creator@GMAIL.COM  ", null, null);

    assertThat(response.getEmail()).isEqualTo("koc.creator@gmail.com");
    ArgumentCaptor<CreatorInvitation> captor = ArgumentCaptor.forClass(CreatorInvitation.class);
    verify(creatorInvitationRepository).save(captor.capture());
    assertThat(captor.getValue().getCampaign()).isNull();
    assertThat(captor.getValue().getInvitedEmail()).isEqualTo("koc.creator@gmail.com");
  }

  @Test
  void sendInvitation_unexpiredPendingInvitationExists_throwsConflict() {
    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId).platform("YOUTUBE").externalId("UC77").userName("koc_77")
        .sourceType(CreatorSourceType.PUBLIC).build();
    CreatorInvitation existingPending = CreatorInvitation.builder()
        .id(UUID.randomUUID()).targetCreator(creator).status("PENDING")
        .expiresAt(LocalDateTime.now().plusDays(3)).build();
    when(creatorRepository.findLockedById(creatorId)).thenReturn(Optional.of(creator));
    when(brandProfileRepository.findByUserId(mockBrandUser.getId())).thenReturn(Optional.of(
        BrandProfile.builder().id(UUID.randomUUID()).user(mockBrandUser).businessName("Acme").build()));
    when(creatorInvitationRepository.findFirstByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
            eq(creatorId), eq("PENDING"), any())).thenReturn(Optional.of(existingPending));

    assertThatThrownBy(() -> invitationService.sendInvitation(creatorId, "koc@gmail.com", null, null))
        .isInstanceOf(ConflictException.class)
        .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo(ErrorCode.CREATOR_INVITATION_PENDING));
    verify(creatorInvitationRepository, never()).save(any());
  }

  @Test
  void sendInvitation_foreignCampaignThrowsNotFound() {
    UUID creatorId = UUID.randomUUID();
    UUID campaignId = UUID.randomUUID();
    BrandProfile myBrand = BrandProfile.builder().id(UUID.randomUUID()).user(mockBrandUser).build();
    BrandProfile otherBrand = BrandProfile.builder().id(UUID.randomUUID()).user(User.builder().id(UUID.randomUUID()).build()).build();
    com.influencermatch.backend.campaign.model.Campaign foreignCampaign =
        com.influencermatch.backend.campaign.model.Campaign.builder().id(campaignId).brandProfile(otherBrand).build();

    when(brandProfileRepository.findByUserId(mockBrandUser.getId())).thenReturn(Optional.of(myBrand));
    when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(foreignCampaign));

    assertThatThrownBy(() -> invitationService.sendInvitation(creatorId, "koc@gmail.com", null, campaignId))
        .isInstanceOf(com.influencermatch.backend.exception.NotFoundException.class);
    verify(creatorInvitationRepository, never()).save(any());
  }
}
