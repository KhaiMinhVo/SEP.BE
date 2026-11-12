package com.influencermatch.backend.auth.service;
import com.influencermatch.backend.auth.repository.EmailVerificationDeliveryRepository;
import com.influencermatch.backend.auth.model.EmailVerificationDelivery;
import com.influencermatch.backend.exception.*;import java.time.*;import java.util.*;
public final class OtpChallengePolicy {
 private OtpChallengePolicy(){}
 public enum Purpose {EMAIL_VERIFICATION,PASSWORD_SETUP}
 private static boolean matches(EmailOtpCodes crypto,UUID id,Purpose purpose,String raw,String hash){return purpose==Purpose.EMAIL_VERIFICATION?crypto.matches(id,raw,hash):crypto.matches(id,"password-setup",raw,hash);}
 public static String issue(OtpChallengeState state,UUID id,Purpose purpose,EmailOtpCodes crypto,Clock clock,EmailVerificationDeliveryRepository deliveries){
  Instant now=clock.instant();
  if((state.getLastSentAt()!=null&&now.isBefore(state.getLastSentAt().plusSeconds(60)))||deliveries.countByUserIdAndSentAtAfter(id,now.minusSeconds(3600))>=5)
   throw new BusinessException(ErrorCode.EMAIL_OTP_RATE_LIMITED,"Please wait before requesting another code");
  String raw=null;
  for(int i=0;i<10;i++){String candidate=crypto.generate();if(state.getCodeHash()==null||!matches(crypto,id,purpose,candidate,state.getCodeHash())){raw=candidate;break;}}
  if(raw==null)throw new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Unable to generate a new code");
  state.setUserId(id);state.setCodeHash(purpose==Purpose.EMAIL_VERIFICATION?crypto.hash(id,raw):crypto.hash(id,"password-setup",raw));
  state.setExpiresAt(now.plusSeconds(600));state.setLastSentAt(now);state.setAttempts(0);state.setConsumedAt(null);
  deliveries.deleteByUserIdAndSentAtBefore(id,now.minusSeconds(3600));deliveries.save(new EmailVerificationDelivery(UUID.randomUUID(),id,now));return raw;
 }
 public static void verify(OtpChallengeState state,Purpose purpose,String raw,EmailOtpCodes crypto,Clock clock){
  if(state.getAttempts()>=5)throw new EmailOtpAttemptException(ErrorCode.EMAIL_OTP_RATE_LIMITED);
  if(state.getConsumedAt()!=null||!state.getExpiresAt().isAfter(clock.instant()))throw new EmailOtpAttemptException(ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED);
  if(!matches(crypto,state.getUserId(),purpose,raw,state.getCodeHash())){
   state.setAttempts(state.getAttempts()+1);throw new EmailOtpAttemptException(state.getAttempts()>=5?ErrorCode.EMAIL_OTP_RATE_LIMITED:ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED);
  }
  state.setConsumedAt(clock.instant());
 }
}
