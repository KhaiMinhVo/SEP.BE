package com.influencermatch.backend.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.auth.dto.CreatorRegistrationDtos.*;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class CreatorInvitationServiceTest {
  @Mock UserRepository users;
  @Mock com.influencermatch.backend.creator.repository.CreatorMasterRepository masters;
  @Mock CreatorInvitationRepository invitations;
  @Mock CreatorInvitationDeliveryRepository deliveries;
  @Mock VerificationEmailSender sender;
  @Mock EmailVerificationService verification;
  @Mock PasswordEncoder encoder;
  @Mock AuditService audit;
  CreatorInvitationService service;
  Instant now=Instant.parse("2026-10-22T00:00:00Z");
  @BeforeEach void setup() {
    service=new CreatorInvitationService(users,masters,invitations,deliveries,sender,verification,encoder,audit,Clock.fixed(now,ZoneOffset.UTC),"https://frontend.example.test/");
    var admin=User.builder().id(UUID.randomUUID()).role(Role.ADMIN).status(UserStatus.ACTIVE).build();
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin,null,admin.getAuthorities()));
  }
  @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
  CreatorInvitation invitation() {
    var i=new CreatorInvitation();i.setId(UUID.randomUUID());i.setEmail("creator@example.test");i.setExpiresAt(now.plusSeconds(172800));return i;
  }
  @Test void invitationNormalizesEmailStoresHashAndAuditsOnlyMetadata() {
    var result=service.invite(new InvitationRequest("  CREATOR@EXAMPLE.TEST  "));
    var saved=ArgumentCaptor.forClass(CreatorInvitation.class);verify(invitations).save(saved.capture());
    var link=ArgumentCaptor.forClass(String.class);verify(sender).sendCreatorInvitation(eq("creator@example.test"),link.capture());
    String token=java.net.URI.create(link.getValue()).getQuery().substring(6);
    assertThat(token).hasSize(43);assertThat(saved.getValue().getTokenHash()).hasSize(64).isNotEqualTo(token);
    assertThat(link.getValue()).startsWith("https://frontend.example.test/auth/creator/register?token=");
    assertThat(result.expiresAt()).isEqualTo(now.plusSeconds(172800));
    verify(audit).record(eq("INVITE_CREATOR"),eq("CreatorInvitation"),eq(result.id()),isNull(),eq(Map.of("email",result.email(),"expiresAt",result.expiresAt().toString())),anyString());
  }
  @Test void existingAccountIsNeverConvertedOrEmailed() {
    when(users.existsByEmail("creator@example.test")).thenReturn(true);
    assertThatThrownBy(()->service.invite(new InvitationRequest("creator@example.test"))).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS));
    verifyNoInteractions(invitations,deliveries,verification,audit);verify(sender,never()).sendCreatorInvitation(anyString(),anyString());
  }
  @ParameterizedTest @ValueSource(strings={"cooldown","budget"})
  void rateLimitNeverReplacesToken(String limit) {
    var i=invitation();i.setLastSentAt(now.minusSeconds(limit.equals("cooldown")?59:60));when(invitations.findByEmail(i.getEmail())).thenReturn(Optional.of(i));
    if(limit.equals("budget"))when(deliveries.countByEmailAndSentAtAfter(i.getEmail(),now.minusSeconds(3600))).thenReturn(5L);
    assertThatThrownBy(()->service.invite(new InvitationRequest(i.getEmail()))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_OTP_RATE_LIMITED));
    verify(invitations,never()).save(any());verify(sender,never()).sendCreatorInvitation(anyString(),anyString());verifyNoInteractions(audit);
  }
  @ParameterizedTest @ValueSource(strings={"expired","consumed","missing"})
  void invalidInvitationNeverCreatesAccount(String state) {
    var i=invitation();if(state.equals("expired"))i.setExpiresAt(now);if(state.equals("consumed"))i.setConsumedAt(now);
    when(invitations.findByTokenHash(anyString())).thenReturn(state.equals("missing")?Optional.empty():Optional.of(i));
    assertThatThrownBy(()->service.register(new RegisterCreatorRequest("token","Password123!","Creator"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.CREATOR_INVITATION_INVALID));
    verify(users,never()).saveAndFlush(any());verifyNoInteractions(verification,encoder);
  }
  @Test void registrationUsesInvitedEmailAndCreatorRoleAndRequiresOtp() {
    var i=invitation();when(invitations.findByTokenHash(anyString())).thenReturn(Optional.of(i));when(encoder.encode("Password123!")).thenReturn("bcrypt");
    when(users.saveAndFlush(any())).thenAnswer(a->a.getArgument(0));
    service.register(new RegisterCreatorRequest("token","Password123!","  Creator  "));
    var actor=ArgumentCaptor.forClass(User.class);verify(users).saveAndFlush(actor.capture());
    assertThat(actor.getValue().getEmail()).isEqualTo(i.getEmail());assertThat(actor.getValue().getRole()).isEqualTo(Role.CREATOR);
    assertThat(actor.getValue().isEmailVerified()).isFalse();assertThat(actor.getValue().getFullName()).isEqualTo("Creator");
    verify(verification).sendInitial(actor.getValue());assertThat(i.getConsumedAt()).isEqualTo(now);
  }
}
