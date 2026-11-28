package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.config.PayOsConfig;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vn.payos.PayOS;
import vn.payos.exception.PayOSException;
import vn.payos.exception.WebhookException;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkResponse;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.webhooks.WebhookData;

@Component
@RequiredArgsConstructor
@Slf4j
public class PayOsPaymentClient {
  private final PayOsConfig config;

  public String createPaymentLink(long orderCode, long amount, String description) {
    PayOS client = configuredClient();
    long expiredAt =
        java.time.Instant.now().plus(java.time.Duration.ofMinutes(15)).getEpochSecond();
    CreatePaymentLinkRequest request =
        CreatePaymentLinkRequest.builder()
            .orderCode(orderCode)
            .amount(amount)
            .description(description)
            .returnUrl(config.getReturnUrl())
            .cancelUrl(config.getCancelUrl())
            .expiredAt(expiredAt)
            .build();
    try {
      CreatePaymentLinkResponse response = client.paymentRequests().create(request);
      if (response == null
          || response.getOrderCode() == null
          || response.getOrderCode() != orderCode
          || response.getAmount() == null
          || response.getAmount() != amount
          || response.getCheckoutUrl() == null
          || response.getCheckoutUrl().isBlank()) {
        throw new IllegalStateException("PayOS returned an incomplete or mismatched checkout link");
      }
      return response.getCheckoutUrl();
    } catch (PayOSException exception) {
      log.error("PayOS checkout creation failed for orderCode={}", orderCode, exception);
      throw new BusinessException(
          ErrorCode.INTERNAL_ERROR, "Payment provider is temporarily unavailable", exception);
    }
  }

  public WebhookData verifyWebhook(String rawBody) {
    try {
      return configuredClient().webhooks().verify(rawBody);
    } catch (WebhookException exception) {
      throw exception;
    } catch (PayOSException exception) {
      log.warn("PayOS webhook could not be verified", exception);
      throw new WebhookException("Invalid webhook data", exception);
    }
  }

  public PaymentLink getPaymentLink(long orderCode) {
    try {
      return configuredClient().paymentRequests().get(orderCode);
    } catch (PayOSException exception) {
      log.warn("PayOS payment-link query failed for orderCode={}", orderCode);
      throw new IllegalStateException("Unable to query PayOS payment link", exception);
    }
  }

  public PaymentLink cancelPaymentLink(long orderCode) {
    try {
      return configuredClient()
          .paymentRequests()
          .cancel(orderCode, "Payment was not completed within 15 minutes");
    } catch (PayOSException exception) {
      log.warn("PayOS payment-link cancellation failed for orderCode={}", orderCode);
      throw new IllegalStateException("Unable to cancel PayOS payment link", exception);
    }
  }

  private PayOS configuredClient() {
    if (!config.isConfigured()) {
      throw new BusinessException(
          ErrorCode.INTERNAL_ERROR, "Payment provider is not configured");
    }
    return new PayOS(config.getClientId(), config.getApiKey(), config.getChecksumKey());
  }
}
