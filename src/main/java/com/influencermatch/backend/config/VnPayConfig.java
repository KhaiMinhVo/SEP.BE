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
  private String queryUrl = "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction";
  private String queryIpAddress = "127.0.0.1";
  private boolean reconciliationEnabled = true;
  private boolean refundEnabled = false;
  private String tmnCode;
  private String hashSecret;
  private String payUrl;
  private String returnUrl;
  private String frontendReturnUrl = "https://www.influencermatch.site/subscription";
}
