package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.auth.dto.CreatorRegistrationDtos.PasswordSetupConfirm;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PasswordSetupService {
  private final UserRepository users;
  private final ExternalIdentityRepository identities;
  private final PasswordSetupOtpRepository codes;
  private final EmailVerificationDeliveryRepository deliveries;
  private final VerificationEmailSender sender;
  private final EmailOtpCodes crypto;
  private final PasswordEncoder encoder;
  private final RefreshTokenService refresh;
  private final Clock clock;

  @Transactional public void request(String email) {
    sender.requireConfigured();
    User user=users.findLockedByEmail(normalize(email)).orElse(null);
    if(!eligible(user))return;
    var otp=codes.findById(user.getId()).orElseGet(PasswordSetupOtp::new);
    String raw=OtpChallengePolicy.issue(otp,user.getId(),OtpChallengePolicy.Purpose.PASSWORD_SETUP,crypto,clock,deliveries);
    codes.save(otp);sender.sendPasswordSetup(user.getEmail(),raw);
  }
  @Transactional(noRollbackFor=EmailOtpAttemptException.class)
  public void confirm(PasswordSetupConfirm request) {
    User user=users.findLockedByEmail(normalize(request.email())).orElse(null);
    if(!eligible(user))throw invalid();
    var otp=codes.findById(user.getId()).orElseThrow(this::invalid);
    OtpChallengePolicy.verify(otp,OtpChallengePolicy.Purpose.PASSWORD_SETUP,request.otp(),crypto,clock);
    user.setPasswordHash(encoder.encode(request.newPassword()));user.setAuthVersion(user.getAuthVersion()+1);
    refresh.revokeAll(user.getId());otp.setConsumedAt(clock.instant());
  }
  private boolean eligible(User user) {
    return user!=null && user.isEmailVerified() && user.getStatus()==UserStatus.ACTIVE && user.getPasswordHash()==null
        && (user.getRole()==Role.BRAND || user.getRole()==Role.CREATOR)
        && identities.findByUserIdAndProvider(user.getId(),ExternalIdentityProvider.GOOGLE).isPresent();
  }
  private EmailOtpAttemptException invalid() {return new EmailOtpAttemptException(ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED);}
  private String normalize(String email) {return email.trim().toLowerCase(Locale.ROOT);}
}
