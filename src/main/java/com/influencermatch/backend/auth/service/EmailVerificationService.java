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

  public void requireConfigured(){sender.requireConfigured();}

  /** Called inside the registration transaction for a newly inserted account. */
  @Transactional
  public void sendInitial(User user) { sender.requireConfigured();send(user,new EmailVerificationOtp()); }

  @Transactional
  public void resend(String email) {
    sender.requireConfigured();
    User user=users.findLockedByEmail(normalize(email)).orElse(null);
    if(user==null || user.isEmailVerified())return;
    var otp=codes.findById(user.getId()).orElseGet(EmailVerificationOtp::new);
    send(user,otp);
  }

  @Transactional(noRollbackFor=EmailOtpAttemptException.class)
  public void verify(String email,String code) {
    // Coordinate with account role/status writes and login before locking the user.
    User user=users.findLockedByEmail(normalize(email)).orElseThrow(()->new EmailOtpAttemptException(ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED));
    if(user.isEmailVerified())throw new BusinessException(ErrorCode.EMAIL_ALREADY_VERIFIED,"Email is already verified");
    var otp=codes.findById(user.getId()).orElseThrow(()->new EmailOtpAttemptException(ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED));
    OtpChallengePolicy.verify(otp,OtpChallengePolicy.Purpose.EMAIL_VERIFICATION,code,crypto,clock);
    user.setEmailVerified(true);
  }

  private void send(User user,EmailVerificationOtp otp) {
    String raw=OtpChallengePolicy.issue(otp,user.getId(),OtpChallengePolicy.Purpose.EMAIL_VERIFICATION,crypto,clock,deliveries);
    codes.save(otp);sender.send(user.getEmail(),raw);
  }
  private String normalize(String email) {return email.trim().toLowerCase(Locale.ROOT);}
}
