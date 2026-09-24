package com.influencermatch.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "application.vnpay")
@Getter
@Setter
public class VnPayConfig {
  private String tmnCode;
  private String hashSecret;
  private String payUrl;
  private String returnUrl;
}
