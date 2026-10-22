package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class EmailVerificationService {
  private final UserRepository users;
  private final EmailVerificationOtpRepository codes;
  private final EmailVerificationDeliveryRepository deliveries;
  private final VerificationEmailSender sender;
  private final EmailOtpCodes crypto;
  private final Clock clock;

  /** Called inside the registration transaction for a newly inserted account. */
  @Transactional
  public void sendInitial(User user) { sender.requireConfigured();send(user,new EmailVerificationOtp()); }

  @Transactional
  public void resend(String email) {
    sender.requireConfigured();
    User user=users.findLockedByEmail(normalize(email)).orElse(null);
    if(user==null || user.isEmailVerified())return;
    var otp=codes.findById(user.getId()).orElseGet(EmailVerificationOtp::new);
    Instant now=clock.instant();
    if((otp.getLastSentAt()!=null && now.isBefore(otp.getLastSentAt().plusSeconds(60)))
        || deliveries.countByUserIdAndSentAtAfter(user.getId(),now.minusSeconds(3600))>=5)
      throw new BusinessException(ErrorCode.EMAIL_OTP_RATE_LIMITED,"Please wait before requesting another code");
    send(user,otp);
  }

  @Transactional(noRollbackFor=EmailOtpAttemptException.class)
  public void verify(String email,String code) {
    // Coordinate with account role/status writes and login before locking the user.
    users.lockRoleChanges();
    User user=users.findLockedByEmail(normalize(email)).orElseThrow(()->new EmailOtpAttemptException(ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED));
    if(user.isEmailVerified())throw new BusinessException(ErrorCode.EMAIL_ALREADY_VERIFIED,"Email is already verified");
    var otp=codes.findById(user.getId()).orElseThrow(()->new EmailOtpAttemptException(ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED));
    if(otp.getAttempts()>=5)throw new EmailOtpAttemptException(ErrorCode.EMAIL_OTP_RATE_LIMITED);
    if(otp.getConsumedAt()!=null || !otp.getExpiresAt().isAfter(clock.instant()))
      throw new EmailOtpAttemptException(ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED);
    if(!crypto.matches(user.getId(),code,otp.getCodeHash())) {
      otp.setAttempts(otp.getAttempts()+1);
      throw new EmailOtpAttemptException(otp.getAttempts()>=5?ErrorCode.EMAIL_OTP_RATE_LIMITED:ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED);
    }
    user.setEmailVerified(true);otp.setConsumedAt(clock.instant());
  }

  private void send(User user,EmailVerificationOtp otp) {
    Instant now=clock.instant();String raw=null;
    for(int attempt=0;attempt<10;attempt++) {
      String candidate=crypto.generate();
      if(otp.getCodeHash()==null || !crypto.matches(user.getId(),candidate,otp.getCodeHash())) {raw=candidate;break;}
    }
    if(raw==null)throw new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Unable to generate a new verification code");
    otp.setUserId(user.getId());otp.setCodeHash(crypto.hash(user.getId(),raw));
    otp.setAttempts(0);otp.setConsumedAt(null);otp.setExpiresAt(now.plusSeconds(600));otp.setLastSentAt(now);
    codes.save(otp);deliveries.deleteByUserIdAndSentAtBefore(user.getId(),now.minusSeconds(3600));
    deliveries.save(new EmailVerificationDelivery(UUID.randomUUID(),user.getId(),now));
    sender.send(user.getEmail(),raw);
  }
  private String normalize(String email) {return email.trim().toLowerCase(Locale.ROOT);}
}
