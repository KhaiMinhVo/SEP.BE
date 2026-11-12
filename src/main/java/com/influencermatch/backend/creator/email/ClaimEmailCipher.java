package com.influencermatch.backend.creator.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.exception.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Dedicated key, authenticated ciphertext; never log plaintext or underlying crypto exceptions. */
@Component
public class ClaimEmailCipher {
  public record Payload(String email,String link) {}
  private final String configuredKey;
  private final ObjectMapper json;
  private final SecureRandom random=new SecureRandom();
  public ClaimEmailCipher(@Value("${application.claim-email.encryption-key:}") String key,ObjectMapper json) {
    this.configuredKey=key;this.json=json;
  }
  private SecretKeySpec key() {
    try {
      byte[] bytes=Base64.getDecoder().decode(configuredKey);
      if(bytes.length!=32)throw new IllegalArgumentException();
      return new SecretKeySpec(bytes,"AES");
    } catch(Exception e) {throw unavailable();}
  }
  public void requireConfigured(){key();}
  private BusinessException unavailable(){return new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Claim email encryption is unavailable");}
  public String encrypt(String email,String link) {
    try {
      byte[] iv=new byte[12];random.nextBytes(iv);
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,iv));
      byte[] encrypted=cipher.doFinal(json.writeValueAsBytes(new Payload(email,link)));
      return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12+encrypted.length).put(iv).put(encrypted).array());
    } catch(Exception e){throw unavailable();}
  }
  public Payload decrypt(String encoded) {
    try {
      byte[] bytes=Base64.getDecoder().decode(encoded);
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,bytes,0,12));
      return json.readValue(cipher.doFinal(bytes,12,bytes.length-12),Payload.class);
    } catch(Exception e){throw unavailable();}
  }
}
