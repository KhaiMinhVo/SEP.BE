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

    invitationService = new CreatorInvitationService(
        creatorRepository,
        creatorInvitationRepository,
        brandProfileRepository,
        campaignRepository,
        userRepository
    );

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
  void sendInvitation_success() {
    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId)
        .platform("YOUTUBE")
        .externalId("UC12345")
        .userName("tech_review")
        .sourceType(CreatorSourceType.PUBLIC)
        .build();

    when(creatorRepository.findById(creatorId)).thenReturn(Optional.of(creator));
    when(brandProfileRepository.findByUserId(mockBrandUser.getId())).thenReturn(Optional.of(
        BrandProfile.builder().id(UUID.randomUUID()).user(mockBrandUser).businessName("Acme").build()
    ));
    when(creatorInvitationRepository.save(any())).thenAnswer(i -> {
      CreatorInvitation inv = i.getArgument(0);
      inv.setId(UUID.randomUUID());
      return inv;
    });

    InvitationResponse response = invitationService.sendInvitation(
        creatorId, "koc@gmail.com", null, null);

    assertThat(response.getCreatorId()).isEqualTo(creatorId);
    assertThat(response.getEmail()).isEqualTo("koc@gmail.com");
    assertThat(response.getStatus()).isEqualTo("PENDING");
    assertThat(response.getRawToken()).isNotBlank();

    ArgumentCaptor<CreatorInvitation> captor = ArgumentCaptor.forClass(CreatorInvitation.class);
    verify(creatorInvitationRepository).save(captor.capture());
    CreatorInvitation saved = captor.getValue();
    assertThat(saved.getTargetCreator()).isEqualTo(creator);
    assertThat(saved.getTokenHash()).isEqualTo(CreatorInvitationService.hashToken(response.getRawToken()));
    assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(6));
  }

  @Test
  void sendInvitation_alreadyClaimed_throwsConflict() {
    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .id(creatorId)
        .platform("YOUTUBE")
        .externalId("UC12345")
        .master(CreatorMaster.builder().id(UUID.randomUUID()).build())
        .sourceType(CreatorSourceType.AUTHORIZED)
        .build();

    when(creatorRepository.findById(creatorId)).thenReturn(Optional.of(creator));

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

    assertThat(invitation.getStatus()).isEqualTo("EXPIRED");
    verify(creatorInvitationRepository).save(invitation);
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
}
