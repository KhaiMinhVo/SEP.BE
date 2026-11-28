package com.influencermatch.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "application.payos")
@Getter
@Setter
public class PayOsConfig {
  private String clientId;
  private String apiKey;
  private String checksumKey;
  private String returnUrl = "https://api.influencermatch.site/api/v1/billing/payos/return";
  private String cancelUrl = "https://api.influencermatch.site/api/v1/billing/payos/return";
  private String frontendReturnUrl = "https://www.influencermatch.site/subscription";

  public boolean isConfigured() {
    return hasText(clientId) && hasText(apiKey) && hasText(checksumKey);
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
