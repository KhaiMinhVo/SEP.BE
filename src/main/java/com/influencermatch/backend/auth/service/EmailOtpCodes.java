package com.influencermatch.backend.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.influencermatch.backend.exception.*;

@Component
public class EmailOtpCodes {
  private final SecureRandom random=new SecureRandom();
  private final String secret;
  public EmailOtpCodes(@Value("${application.auth.email-verification.secret:}") String secret) {this.secret=secret;}
  public String generate() {return String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));}
  public String hash(UUID userId,String code) {return hash(userId,"email-verification",code);}
  public String hash(UUID userId,String purpose,String code) {
    if(secret.length()<32)throw new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"OTP secret is not configured");
    try {
      Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal((purpose+":"+userId+":"+code).getBytes(StandardCharsets.UTF_8)));
    } catch(GeneralSecurityException e) {throw new IllegalStateException("Cannot protect verification code",e);}
  }
  public boolean matches(UUID id,String purpose,String code,String stored) {
    return MessageDigest.isEqual(hash(id,purpose,code).getBytes(StandardCharsets.US_ASCII),stored.getBytes(StandardCharsets.US_ASCII));
  }
  public boolean matches(UUID id,String code,String stored) {
    return MessageDigest.isEqual(hash(id,code).getBytes(StandardCharsets.US_ASCII),stored.getBytes(StandardCharsets.US_ASCII));
  }
}
