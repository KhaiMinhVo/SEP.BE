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
  private String returnUrl = "https://www.influencermatch.site/subscription";
  private String cancelUrl = "https://www.influencermatch.site/subscription?payment=cancelled";

  public boolean isConfigured() {
    return hasText(clientId) && hasText(apiKey) && hasText(checksumKey);
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
