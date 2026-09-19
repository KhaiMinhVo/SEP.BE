package com.influencermatch.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionSecurityGuard {
  private static final String DEMO =
      "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

  @Value("${application.security.jwt.secret-key:}")
  private String secret;

  @Value("${application.cors.allowed-origins:}")
  private String origins;

  @PostConstruct
  void validate() {
    if (secret.isBlank() || DEMO.equals(secret))
      throw new IllegalStateException("Production JWT secret must be supplied securely");
    if (origins.contains("*"))
      throw new IllegalStateException("Wildcard CORS origin is forbidden in production");
  }
}
