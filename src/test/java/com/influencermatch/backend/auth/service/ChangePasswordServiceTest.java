package com.influencermatch.backend.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.auth.dto.ChangePasswordRequest;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.validation.Validation;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class ChangePasswordServiceTest {
  final UserRepository users = mock(UserRepository.class);
  final AuthSessionStore sessions = mock(AuthSessionStore.class);
  final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
  final ChangePasswordService service = new ChangePasswordService(users, encoder, sessions);
  User user;

  @BeforeEach void setup() {
    user = User.builder().id(UUID.randomUUID()).email("test@example.com")
        .passwordHash(encoder.encode("OldPassword123!")).authVersion(4).build();
    when(users.findLockedById(user.getId())).thenReturn(Optional.of(user));
  }

  @Test void changesHashAndRevokesAllSessions() {
    service.change(user, new ChangePasswordRequest("OldPassword123!", "NewPassword123!"));
    assertThat(encoder.matches("NewPassword123!", user.getPasswordHash())).isTrue();
    assertThat(encoder.matches("OldPassword123!", user.getPasswordHash())).isFalse();
    assertThat(user.getAuthVersion()).isEqualTo(5);
    verify(users).save(user);
    verify(sessions).revokeAll(user.getId());
  }

  @Test void wrongCurrentPasswordDoesNotMutateAccount() {
    rejectsWithoutMutation(new ChangePasswordRequest("WrongPassword123!", "NewPassword123!"));
  }

  @Test void cannotReuseCurrentPassword() {
    rejectsWithoutMutation(new ChangePasswordRequest("OldPassword123!", "OldPassword123!"));
  }

  @Test void passwordlessAccountCannotSkipCurrentPasswordCheck() {
    user.setPasswordHash(null);
    rejectsWithoutMutation(new ChangePasswordRequest("OldPassword123!", "NewPassword123!"));
  }

  @Test void rejectsPasswordsExceedingBcryptByteLimit() {
    rejectsWithoutMutation(new ChangePasswordRequest("OldPassword123!", "あ".repeat(25)));
  }

  @Test void revokedSessionCannotChangePassword() {
    doThrow(new BusinessException(ErrorCode.UNAUTHENTICATED, "Session revoked"))
        .when(sessions).require(null, user, true);
    rejectsWithoutMutation(new ChangePasswordRequest("OldPassword123!", "NewPassword123!"));
  }

  @Test void validatesMissingAndShortPasswords() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertThat(validator.validate(new ChangePasswordRequest("", "short"))).isNotEmpty();
      assertThat(validator.validate(new ChangePasswordRequest("old", "NewPassword123!"))).isEmpty();
    }
  }

  private void rejectsWithoutMutation(ChangePasswordRequest request) {
    String original = user.getPasswordHash();
    assertThatThrownBy(() -> service.change(user, request)).isInstanceOf(BusinessException.class);
    assertThat(user.getPasswordHash()).isEqualTo(original);
    assertThat(user.getAuthVersion()).isEqualTo(4);
    verify(users, never()).save(any());
    verify(sessions, never()).revokeAll(any());
  }
}
