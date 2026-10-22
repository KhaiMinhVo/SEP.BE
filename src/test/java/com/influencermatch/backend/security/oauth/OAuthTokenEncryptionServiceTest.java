package com.influencermatch.backend.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OAuthTokenEncryptionServiceTest {

  private OAuthTokenEncryptionService encryptionService;
  private final String testKey = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

  @BeforeEach
  void setUp() {
    encryptionService = new OAuthTokenEncryptionService();
    ReflectionTestUtils.setField(encryptionService, "secretKey", testKey);
  }

  @Test
  @DisplayName("Encrypt and decrypt roundtrip restores original raw token")
  void roundTripEncryptionDecryption() {
    String rawToken = "ya29.a0AfH6SMD_MockOAuthAccessToken_XYZ123";

    String encrypted = encryptionService.encrypt(rawToken);
    assertThat(encrypted).isNotNull();
    assertThat(encrypted).isNotEqualTo(rawToken);

    String decrypted = encryptionService.decrypt(encrypted);
    assertThat(decrypted).isEqualTo(rawToken);
  }

  @Test
  @DisplayName("Multiple encryptions of the same token yield different ciphertexts due to random IV")
  void randomIvProducesDistinctCiphertexts() {
    String rawToken = "same-refresh-token-value";

    String encrypted1 = encryptionService.encrypt(rawToken);
    String encrypted2 = encryptionService.encrypt(rawToken);

    assertThat(encrypted1).isNotEqualTo(encrypted2);
    assertThat(encryptionService.decrypt(encrypted1)).isEqualTo(rawToken);
    assertThat(encryptionService.decrypt(encrypted2)).isEqualTo(rawToken);
  }

  @Test
  @DisplayName("Null or empty token returns unchanged")
  void nullOrEmptyTokenHandling() {
    assertThat(encryptionService.encrypt(null)).isNull();
    assertThat(encryptionService.encrypt("")).isEmpty();
    assertThat(encryptionService.decrypt(null)).isNull();
    assertThat(encryptionService.decrypt("")).isEmpty();
  }

  @Test
  @DisplayName("Decrypting corrupted ciphertext or invalid auth tag throws IllegalStateException")
  void decryptCorruptedPayloadThrowsException() {
    String rawToken = "secret-token";
    String encrypted = encryptionService.encrypt(rawToken);

    // Tamper with the base64 string
    byte[] decoded = java.util.Base64.getDecoder().decode(encrypted);
    decoded[decoded.length - 1] ^= 0xFF; // Flip bits in the auth tag
    String corrupted = java.util.Base64.getEncoder().encodeToString(decoded);

    assertThatThrownBy(() -> encryptionService.decrypt(corrupted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to decrypt OAuth token");
  }

  @Test
  @DisplayName("Decrypting truncated or invalid base64 throws IllegalStateException")
  void decryptInvalidBase64ThrowsException() {
    assertThatThrownBy(() -> encryptionService.decrypt("not-valid-base64!!!"))
        .isInstanceOf(IllegalStateException.class);

    assertThatThrownBy(() -> encryptionService.decrypt("dG9vLXNob3J0")) // "too-short" < 12 bytes IV
        .isInstanceOf(IllegalStateException.class);
  }
}
