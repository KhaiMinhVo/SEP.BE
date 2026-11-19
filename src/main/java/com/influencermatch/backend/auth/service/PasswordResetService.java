package com.influencermatch.backend.auth.service;

import static com.influencermatch.backend.auth.service.OtpChallengePolicy.Purpose.PASSWORD_RESET;

import com.influencermatch.backend.auth.model.PasswordResetOtp;
import com.influencermatch.backend.auth.repository.EmailVerificationDeliveryRepository;
import com.influencermatch.backend.auth.repository.PasswordResetOtpRepository;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.EmailOtpAttemptException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {
  private final UserRepository users;
  private final PasswordResetOtpRepository codes;
  private final EmailVerificationDeliveryRepository deliveries;
  private final VerificationEmailSender sender;
  private final EmailOtpCodes crypto;
  private final PasswordEncoder encoder;
  private final AuthSessionStore sessions;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public void request(String email) {
    try {
      sender.requireConfigured();
    } catch (BusinessException ex) {
      log.warn("Password reset email service is unavailable");
      throw new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE, "Email service is unavailable");
    }
    try {
      transaction.executeWithoutResult(status -> issueAndSend(normalize(email)));
    } catch (BusinessException ex) {
      if (ex.code() == ErrorCode.EMAIL_OTP_RATE_LIMITED) return;
      if (ex.code() != ErrorCode.EMAIL_DELIVERY_UNAVAILABLE) throw ex;
      // The failed transaction has rolled back before returning the generic response.
      log.warn("Password reset OTP delivery failed");
    }
  }

  private void issueAndSend(String normalizedEmail) {
    User user = users.findLockedByEmail(normalizedEmail).orElse(null);
    if (!eligible(user)) return;
    var otp = codes.findById(user.getId()).orElseGet(PasswordResetOtp::new);
    String raw = OtpChallengePolicy.issue(otp, user.getId(), PASSWORD_RESET, crypto, clock, deliveries);
    otp.setAuthVersion(user.getAuthVersion());
    codes.saveAndFlush(otp);
    sender.sendPasswordReset(user.getEmail(), raw);
  }

  @Transactional(noRollbackFor = EmailOtpAttemptException.class)
  public void confirm(String email, String rawOtp, String newPassword) {
    if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "New password must not exceed 72 UTF-8 bytes");
    }
    User user = users.findLockedByEmail(normalize(email)).orElse(null);
    if (!eligible(user)) throw invalid();
    var otp = codes.findById(user.getId()).orElseThrow(PasswordResetService::invalid);
    if (otp.getAuthVersion() != user.getAuthVersion()) throw invalid();

    try {
      OtpChallengePolicy.verify(otp, PASSWORD_RESET, rawOtp, crypto, clock);
    } catch (EmailOtpAttemptException ex) {
      // Persist failed attempts and use the same outward response once the code is locked.
      throw invalid();
    }

    user.setPasswordHash(encoder.encode(newPassword));
    user.setAuthVersion(user.getAuthVersion() + 1);
    sessions.revokeAll(user.getId());
  }

  private static boolean eligible(User user) {
    return user != null && user.isEmailVerified() && user.getStatus() == UserStatus.ACTIVE
        && (user.getRole() == Role.BRAND || user.getRole() == Role.CREATOR)
        && user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
  }

  private static EmailOtpAttemptException invalid() {
    return new EmailOtpAttemptException(ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED);
  }

  private static String normalize(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }
}
