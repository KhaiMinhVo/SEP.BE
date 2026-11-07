package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Verifies signatures using Google's fixed JWKS endpoint; never trusts client profile fields. */
@Component
public class GoogleIdTokenVerifier {
  private final JwtDecoder decoder;
  private final String clientId;
  private final boolean enabled;

  @org.springframework.beans.factory.annotation.Autowired
  public GoogleIdTokenVerifier(
      @Value("${application.google-login.enabled:false}") boolean enabled,
      @Value("${application.google-login.client-id:}") String clientId,
      Clock clock) {
    this.enabled = enabled;
    this.clientId = clientId;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5000);
    factory.setReadTimeout(5000);
    var googleDecoder =
        NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
            .restOperations(new RestTemplate(factory))
            .build();
    googleDecoder.setJwtValidator(validators(clientId, clock));
    decoder = googleDecoder;
  }

  GoogleIdTokenVerifier(boolean enabled, String clientId, JwtDecoder decoder) {
    this.enabled = enabled;
    this.clientId = clientId;
    this.decoder = decoder;
  }

  static OAuth2TokenValidator<Jwt> validators(String clientId, Clock clock) {
    var timestamp = new JwtTimestampValidator(Duration.ofSeconds(30));
    timestamp.setClock(clock);
    return new DelegatingOAuth2TokenValidator<>(
        timestamp,
        jwt -> {
          String issuer = jwt.getClaimAsString("iss");
          boolean valid =
              ("https://accounts.google.com".equals(issuer) || "accounts.google.com".equals(issuer))
                  && jwt.getAudience().contains(clientId)
                  && (jwt.getAudience().size() == 1 || clientId.equals(jwt.getClaimAsString("azp")))
                  && (jwt.getClaimAsString("azp") == null
                      || clientId.equals(jwt.getClaimAsString("azp")))
                  && jwt.getExpiresAt() != null;
          return valid
              ? OAuth2TokenValidatorResult.success()
              : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        });
  }

  public String requireConfigured() {
    if (!enabled || clientId.isBlank() || !clientId.endsWith(".apps.googleusercontent.com"))
      throw new BusinessException(
          ErrorCode.GOOGLE_LOGIN_UNAVAILABLE, "Google Login is not configured");
    return clientId;
  }

  public Jwt verify(String credential) {
    requireConfigured();
    try {
      Jwt token = decoder.decode(credential);
      String email = token.getClaimAsString("email");
      if (!Boolean.TRUE.equals(token.getClaim("email_verified"))
          || token.getSubject() == null
          || token.getSubject().isBlank()
          || token.getSubject().length() > 255
          || email == null
          || email.length() > 255
          || !email.matches("[^\\s@]+@[^\\s@]+")) throw new IllegalArgumentException();
      return token;
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Invalid Google credential");
    }
  }
}
