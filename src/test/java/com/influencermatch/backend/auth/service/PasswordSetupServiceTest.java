package com.influencermatch.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.auth.dto.CreatorRegistrationDtos.PasswordSetupConfirm;
import com.influencermatch.backend.auth.model.ExternalIdentity;
import com.influencermatch.backend.auth.model.ExternalIdentityProvider;
import com.influencermatch.backend.auth.model.PasswordSetupOtp;
import com.influencermatch.backend.auth.repository.EmailVerificationDeliveryRepository;
import com.influencermatch.backend.auth.repository.ExternalIdentityRepository;
import com.influencermatch.backend.auth.repository.PasswordSetupOtpRepository;
import com.influencermatch.backend.exception.EmailOtpAttemptException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PasswordSetupServiceTest {

  @Mock private UserRepository users;
  @Mock private ExternalIdentityRepository identities;
  @Mock private PasswordSetupOtpRepository codes;
  @Mock private EmailVerificationDeliveryRepository deliveries;
  @Mock private VerificationEmailSender sender;
  @Mock private PasswordEncoder encoder;
  @Mock private RefreshTokenService refresh;

  private EmailOtpCodes crypto;
  private PasswordSetupService service;
  private final Instant now = Instant.parse("2026-10-22T00:00:00Z");
  private User user;

  @BeforeEach
  void setUp() {
    crypto = new EmailOtpCodes("test-email-otp-secret-at-least-32-characters");
    service = new PasswordSetupService(
        users,
        identities,
        codes,
        deliveries,
        sender,
        crypto,
        encoder,
        refresh,
        Clock.fixed(now, ZoneOffset.UTC));

    user = User.builder()
        .id(UUID.randomUUID())
        .email("brand@example.test")
        .role(Role.BRAND)
        .status(UserStatus.ACTIVE)
        .emailVerified(true)
        .passwordHash(null)
        .authVersion(1L)
        .build();
  }

  private PasswordSetupOtp validOtp(Instant expiresAt) {
    PasswordSetupOtp otp = new PasswordSetupOtp();
    otp.setUserId(user.getId());
    otp.setCodeHash(crypto.hash(user.getId(), "password-setup", "123456"));
    otp.setExpiresAt(expiresAt);
    otp.setAttempts(0);
    return otp;
  }

  @Test
  @DisplayName("setupNewPassword UTCID01 [N]: Active verified Google-linked BRAND sets password, revokes tokens, consumes OTP")
  void confirm_validGoogleBrandSetsPasswordAndRevokesTokens() {
    PasswordSetupOtp otp = validOtp(now.plusSeconds(300));
    when(users.findLockedByEmail("brand@example.test")).thenReturn(Optional.of(user));
    when(identities.findByUserIdAndProvider(user.getId(), ExternalIdentityProvider.GOOGLE))
        .thenReturn(Optional.of(ExternalIdentity.builder().user(user).provider(ExternalIdentityProvider.GOOGLE).build()));
    when(codes.findById(user.getId())).thenReturn(Optional.of(otp));
    when(encoder.encode("NewPass123!")).thenReturn("$2a$10$encodedHash");

    service.confirm(new PasswordSetupConfirm(" BRAND@EXAMPLE.TEST ", "123456", "NewPass123!"));

    assertThat(user.getPasswordHash()).isEqualTo("$2a$10$encodedHash");
    assertThat(user.getAuthVersion()).isEqualTo(2L);
    assertThat(otp.getConsumedAt()).isEqualTo(now);
    verify(refresh).revokeAll(user.getId());
  }

  @Test
  @DisplayName("setupNewPassword UTCID02 [B]: OTP expiring exactly at now throws EMAIL_OTP_INVALID_OR_EXPIRED")
  void confirm_otpExpiresAtNowRejected() {
    PasswordSetupOtp otp = validOtp(now);
    when(users.findLockedByEmail("brand@example.test")).thenReturn(Optional.of(user));
    when(identities.findByUserIdAndProvider(user.getId(), ExternalIdentityProvider.GOOGLE))
        .thenReturn(Optional.of(ExternalIdentity.builder().user(user).provider(ExternalIdentityProvider.GOOGLE).build()));
    when(codes.findById(user.getId())).thenReturn(Optional.of(otp));

    assertThatThrownBy(() -> service.confirm(new PasswordSetupConfirm("brand@example.test", "123456", "NewPass123!")))
        .isInstanceOfSatisfying(EmailOtpAttemptException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED));
    assertThat(user.getPasswordHash()).isNull();
    verifyNoInteractions(encoder, refresh);
  }

  @Test
  @DisplayName("setupNewPassword UTCID03 [A]: User already having passwordHash throws EMAIL_OTP_INVALID_OR_EXPIRED")
  void confirm_userAlreadyHasPasswordRejected() {
    user.setPasswordHash("existing-hash");
    when(users.findLockedByEmail("brand@example.test")).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.confirm(new PasswordSetupConfirm("brand@example.test", "123456", "NewPass123!")))
        .isInstanceOfSatisfying(EmailOtpAttemptException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED));
    assertThat(user.getPasswordHash()).isEqualTo("existing-hash");
    verifyNoInteractions(codes, encoder, refresh);
  }

  @Test
  @DisplayName("setupNewPassword UTCID04 [A]: Inactive user throws EMAIL_OTP_INVALID_OR_EXPIRED")
  void confirm_inactiveUserRejected() {
    user.setStatus(UserStatus.LOCKED);
    when(users.findLockedByEmail("brand@example.test")).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.confirm(new PasswordSetupConfirm("brand@example.test", "123456", "NewPass123!")))
        .isInstanceOfSatisfying(EmailOtpAttemptException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED));
    verifyNoInteractions(codes, encoder, refresh);
  }

  @Test
  @DisplayName("setupNewPassword UTCID05 [A]: User without Google identity throws EMAIL_OTP_INVALID_OR_EXPIRED")
  void confirm_userWithoutGoogleIdentityRejected() {
    when(users.findLockedByEmail("brand@example.test")).thenReturn(Optional.of(user));
    when(identities.findByUserIdAndProvider(user.getId(), ExternalIdentityProvider.GOOGLE))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.confirm(new PasswordSetupConfirm("brand@example.test", "123456", "NewPass123!")))
        .isInstanceOfSatisfying(EmailOtpAttemptException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED));
    verifyNoInteractions(codes, encoder, refresh);
  }
}
