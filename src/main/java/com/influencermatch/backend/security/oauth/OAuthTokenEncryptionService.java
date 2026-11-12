package com.influencermatch.backend.security.oauth;

import io.jsonwebtoken.io.Decoders;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service providing authenticated AES-256-GCM encryption and decryption for sensitive
 * OAuth provider tokens (Access Tokens, Refresh Tokens) at rest.
 *
 * <p>Uses a 12-byte random Initialization Vector (IV) and a 128-bit authentication tag.
 */
@Slf4j
@Service
public class OAuthTokenEncryptionService {

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int IV_LENGTH_BYTES = 12;
  private static final int TAG_LENGTH_BITS = 128;

  @Value("${application.security.oauth-token.secret-key}")
  private String secretKey;

  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * Encrypts a raw OAuth token string using AES-256-GCM.
   *
   * @param rawToken plaintext token (e.g. access_token or refresh_token)
   * @return Base64-encoded combined IV + cipherText + authTag
   */
  public String encrypt(String rawToken) {
    if (rawToken == null || rawToken.isEmpty()) {
      return rawToken;
    }
    try {
      byte[] iv = new byte[IV_LENGTH_BYTES];
      secureRandom.nextBytes(iv);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      GCMParameterSpec parameterSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
      cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), parameterSpec);

      byte[] cipherText = cipher.doFinal(rawToken.getBytes(StandardCharsets.UTF_8));

      ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
      byteBuffer.put(iv);
      byteBuffer.put(cipherText);

      return Base64.getEncoder().encodeToString(byteBuffer.array());
    } catch (Exception ex) {
      log.error("Failed to encrypt OAuth token: {}", ex.getMessage(), ex);
      throw new IllegalStateException("Failed to encrypt OAuth token", ex);
    }
  }

  /**
   * Decrypts a Base64-encoded combined payload into the original plaintext token.
   *
   * @param encryptedToken Base64-encoded combined IV + cipherText + authTag
   * @return plaintext token
   */
  public String decrypt(String encryptedToken) {
    if (encryptedToken == null || encryptedToken.isEmpty()) {
      return encryptedToken;
    }
    try {
      byte[] combined;
      try {
        combined = Base64.getDecoder().decode(encryptedToken);
      } catch (IllegalArgumentException e) {
        combined = Base64.getUrlDecoder().decode(encryptedToken);
      }

      if (combined.length < IV_LENGTH_BYTES) {
        throw new IllegalArgumentException("Invalid encrypted token length: payload too short");
      }

      byte[] iv = new byte[IV_LENGTH_BYTES];
      System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);

      byte[] cipherText = new byte[combined.length - IV_LENGTH_BYTES];
      System.arraycopy(combined, IV_LENGTH_BYTES, cipherText, 0, cipherText.length);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      GCMParameterSpec parameterSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
      cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), parameterSpec);

      byte[] decrypted = cipher.doFinal(cipherText);
      return new String(decrypted, StandardCharsets.UTF_8);
    } catch (Exception ex) {
      log.error("Failed to decrypt OAuth token: {}", ex.getMessage(), ex);
      throw new IllegalStateException("Failed to decrypt OAuth token", ex);
    }
  }

  private SecretKey getSecretKey() {
    try {
      byte[] keyBytes;
      try {
        keyBytes = Decoders.BASE64.decode(secretKey);
        if (keyBytes.length != 32) {
          keyBytes = MessageDigest.getInstance("SHA-256").digest(secretKey.getBytes(StandardCharsets.UTF_8));
        }
      } catch (Exception e) {
        keyBytes = MessageDigest.getInstance("SHA-256").digest(secretKey.getBytes(StandardCharsets.UTF_8));
      }
      return new SecretKeySpec(keyBytes, "AES");
    } catch (Exception e) {
      throw new IllegalStateException("Failed to initialize AES key for OAuth token encryption", e);
    }
  }
}
