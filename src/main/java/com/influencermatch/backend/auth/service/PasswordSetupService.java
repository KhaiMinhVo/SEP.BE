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
  private static final String PURPOSE="password-setup";
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
    sender.requireConfigured();users.lockRoleChanges();
    User user=users.findLockedByEmail(normalize(email)).orElse(null);
    if(!eligible(user))return;
    var otp=codes.findById(user.getId()).orElseGet(PasswordSetupOtp::new);Instant now=clock.instant();
    if((otp.getLastSentAt()!=null && now.isBefore(otp.getLastSentAt().plusSeconds(60)))
        || deliveries.countByUserIdAndSentAtAfter(user.getId(),now.minusSeconds(3600))>=5)
      throw new BusinessException(ErrorCode.EMAIL_OTP_RATE_LIMITED,"Please wait before requesting another code");
    String raw=null;
    for(int i=0;i<10;i++) {
      var candidate=crypto.generate();
      if(otp.getCodeHash()==null || !crypto.matches(user.getId(),PURPOSE,candidate,otp.getCodeHash())) {raw=candidate;break;}
    }
    if(raw==null)throw new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Unable to generate a new code");
    otp.setUserId(user.getId());otp.setCodeHash(crypto.hash(user.getId(),PURPOSE,raw));
    otp.setExpiresAt(now.plusSeconds(600));otp.setLastSentAt(now);otp.setAttempts(0);otp.setConsumedAt(null);codes.save(otp);
    deliveries.deleteByUserIdAndSentAtBefore(user.getId(),now.minusSeconds(3600));
    deliveries.save(new EmailVerificationDelivery(UUID.randomUUID(),user.getId(),now));sender.sendPasswordSetup(user.getEmail(),raw);
  }
  @Transactional(noRollbackFor=EmailOtpAttemptException.class)
  public void confirm(PasswordSetupConfirm request) {
    users.lockRoleChanges();User user=users.findLockedByEmail(normalize(request.email())).orElse(null);
    if(!eligible(user))throw invalid();
    var otp=codes.findById(user.getId()).orElseThrow(this::invalid);
    if(otp.getAttempts()>=5)throw new EmailOtpAttemptException(ErrorCode.EMAIL_OTP_RATE_LIMITED);
    if(otp.getConsumedAt()!=null || !otp.getExpiresAt().isAfter(clock.instant()))throw invalid();
    if(!crypto.matches(user.getId(),PURPOSE,request.otp(),otp.getCodeHash())) {
      otp.setAttempts(otp.getAttempts()+1);
      throw new EmailOtpAttemptException(otp.getAttempts()>=5?ErrorCode.EMAIL_OTP_RATE_LIMITED:ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED);
    }
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
