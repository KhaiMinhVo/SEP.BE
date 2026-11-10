package com.influencermatch.backend.creator.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.exception.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MetaPayloadCipher {
  private final MetaOAuthProperties properties;
  private final InstagramOAuthProperties instagram;
  private final ObjectMapper json;
  private final SecureRandom random = new SecureRandom();

  @Autowired
  public MetaPayloadCipher(
      MetaOAuthProperties properties, InstagramOAuthProperties instagram, ObjectMapper json) {
    this.properties = properties;
    this.instagram = instagram;
    this.json = json;
  }

  public MetaPayloadCipher(MetaOAuthProperties properties, ObjectMapper json) {
    this(properties, null, json);
  }

  private String key(OAuthFlow flow) {
    if (flow == OAuthFlow.INSTAGRAM_LOGIN) {
      if (instagram == null) throw unavailable(flow);
      instagram.requireConfigured();
      return instagram.encryptionKey();
    }
    properties.requireConfigured();
    return properties.encryptionKey();
  }

  private Cipher cipher(OAuthFlow flow, int mode, byte[] iv) throws Exception {
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        mode,
        new SecretKeySpec(Base64.getDecoder().decode(key(flow)), "AES"),
        new GCMParameterSpec(128, iv));
    return cipher;
  }

  public String encrypt(MetaDtos.Proof proof) {
    return encrypt(OAuthFlow.FACEBOOK_LOGIN, proof);
  }

  public String encrypt(OAuthFlow flow, MetaDtos.Proof proof) {
    try {
      key(flow);
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      byte[] data = cipher(flow, Cipher.ENCRYPT_MODE, iv).doFinal(json.writeValueAsBytes(proof));
      return Base64.getEncoder()
          .encodeToString(ByteBuffer.allocate(12 + data.length).put(iv).put(data).array());
    } catch (Exception error) {
      throw unavailable(flow);
    }
  }

  public MetaDtos.Proof decrypt(String payload) {
    return decrypt(OAuthFlow.FACEBOOK_LOGIN, payload);
  }

  public MetaDtos.Proof decrypt(OAuthFlow flow, String payload) {
    try {
      key(flow);
      byte[] data = Base64.getDecoder().decode(payload);
      if (data.length < 28) throw new IllegalArgumentException();
      byte[] iv = java.util.Arrays.copyOf(data, 12);
      return json.readValue(
          cipher(flow, Cipher.DECRYPT_MODE, iv).doFinal(data, 12, data.length - 12),
          MetaDtos.Proof.class);
    } catch (Exception error) {
      throw unavailable(flow);
    }
  }

  private BusinessException unavailable(OAuthFlow flow) {
    return new BusinessException(
        flow == OAuthFlow.INSTAGRAM_LOGIN
            ? ErrorCode.INSTAGRAM_OAUTH_UNAVAILABLE
            : ErrorCode.META_OAUTH_UNAVAILABLE,
        "OAuth verification data is unavailable");
  }
}
