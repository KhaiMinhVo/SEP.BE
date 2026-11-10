package com.influencermatch.backend.auth.service;

import static org.assertj.core.api.Assertions.*;

import com.influencermatch.backend.exception.BusinessException;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class GoogleIdTokenVerifierTest {
  static final String CLIENT = "test.apps.googleusercontent.com";
  static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
  com.nimbusds.jose.jwk.RSAKey key;
  GoogleIdTokenVerifier verifier;

  @BeforeEach
  void setup() throws Exception {
    key = new RSAKeyGenerator(2048).generate();
    var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    decoder.setJwtValidator(
        GoogleIdTokenVerifier.validators(CLIENT, Clock.fixed(NOW, ZoneOffset.UTC)));
    verifier = new GoogleIdTokenVerifier(true, CLIENT, decoder);
  }

  JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder()
        .issuer("https://accounts.google.com")
        .audience(CLIENT)
        .subject("google-subject")
        .expirationTime(Date.from(NOW.plusSeconds(300)))
        .issueTime(Date.from(NOW))
        .claim("email", "brand@gmail.com")
        .claim("email_verified", true)
        .claim("nonce", "nonce");
  }

  String sign(JWTClaimsSet.Builder claims) throws Exception {
    var token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
    token.sign(new RSASSASigner(key));
    return token.serialize();
  }

  @Test
  void verifiesRealSignatureAndClaims() throws Exception {
    assertThat(verifier.verify(sign(claims())).getSubject()).isEqualTo("google-subject");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "issuer",
        "audience",
        "expiry",
        "email",
        "verified",
        "subject",
        "azp",
        "multiAudience"
      })
  void rejectsInvalidClaims(String field) throws Exception {
    var c = claims();
    switch (field) {
      case "issuer" -> c.issuer("https://attacker.example");
      case "audience" -> c.audience("other-client");
      case "expiry" -> c.expirationTime(Date.from(NOW.minusSeconds(60)));
      case "email" -> c.claim("email", "invalid");
      case "verified" -> c.claim("email_verified", false);
      case "subject" -> c.subject("");
      case "azp" -> c.claim("azp", "other-client");
      case "multiAudience" -> c.audience(List.of(CLIENT, "other-client"));
    }
    String raw = sign(c);
    assertThatThrownBy(() -> verifier.verify(raw))
        .isInstanceOf(BusinessException.class)
        .hasMessage("Invalid Google credential");
  }

  @Test
  void rejectsForeignSignature() throws Exception {
    var other = new RSAKeyGenerator(2048).generate();
    var token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims().build());
    token.sign(new RSASSASigner(other));
    assertThatThrownBy(() -> verifier.verify(token.serialize()))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void disabledDoesNotAttemptVerification() {
    var disabled =
        new GoogleIdTokenVerifier(
            false,
            CLIENT,
            raw -> {
              throw new AssertionError("network");
            });
    assertThatThrownBy(() -> disabled.verify("secret"))
        .isInstanceOf(BusinessException.class)
        .hasMessage("Google Login is not configured");
  }
}
