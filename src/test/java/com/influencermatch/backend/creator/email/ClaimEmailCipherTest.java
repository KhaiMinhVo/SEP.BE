package com.influencermatch.backend.creator.email;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.exception.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClaimEmailCipherTest {
  static final String KEY="AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
  @Test void authenticatedEncryptionUsesFreshNonceAndPreservesPayload() {
    var cipher=new ClaimEmailCipher(KEY,new ObjectMapper());
    String first=cipher.encrypt("creator@example.test","https://frontend.test/claim?token=secret");
    String second=cipher.encrypt("creator@example.test","https://frontend.test/claim?token=secret");
    assertThat(first).isNotEqualTo(second).doesNotContain("creator@example.test","secret");
    assertThat(cipher.decrypt(first)).isEqualTo(new ClaimEmailCipher.Payload("creator@example.test","https://frontend.test/claim?token=secret"));
    byte[] tampered=Base64.getDecoder().decode(first);tampered[tampered.length-1]^=1;
    assertThatThrownBy(()->cipher.decrypt(Base64.getEncoder().encodeToString(tampered)))
        .isInstanceOf(BusinessException.class).hasMessage("Claim email encryption is unavailable");
    assertThatThrownBy(()->new ClaimEmailCipher(Base64.getEncoder().encodeToString(new byte[32]),new ObjectMapper()).decrypt(first))
        .isInstanceOf(BusinessException.class);
  }
  @ParameterizedTest @ValueSource(strings={"","not-base64","c2hvcnQ="})
  void invalidKeyReturns503WithoutSensitiveDetails(String key) {
    assertThatThrownBy(()->new ClaimEmailCipher(key,new ObjectMapper()).requireConfigured())
        .isInstanceOf(BusinessException.class).satisfies(e->assertThat(((BusinessException)e).code()).isEqualTo(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE));
  }
}
