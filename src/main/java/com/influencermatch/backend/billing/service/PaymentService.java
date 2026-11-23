package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.dto.PaymentResponse;
import com.influencermatch.backend.billing.enums.SubscriptionStatus;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.model.Plan;
import com.influencermatch.backend.billing.model.Subscription;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.billing.repository.PlanRepository;
import com.influencermatch.backend.billing.repository.SubscriptionRepository;
import com.influencermatch.backend.billing.utils.VnPayUtil;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.config.PayOsConfig;
import com.influencermatch.backend.config.VnPayConfig;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import com.influencermatch.backend.security.permission.*;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

  private final PlanRepository planRepository;
  private final SubscriptionRepository subscriptionRepository;
  private final PaymentRepository paymentRepository;
  private final VnPayConfig vnPayConfig;
  private final PayOsConfig payOsConfig;
  private final PayOsPaymentClient payOsPaymentClient;
  private final com.influencermatch.backend.brand.repository.BrandProfileRepository brands;
  private final PaymentSettlementService settlement;

  /** Reuse recent PENDING checkouts to prevent duplicate payments. */
  static final int CHECKOUT_REUSE_WINDOW_MINUTES = 15;

  @Transactional
  public PaymentResponse createCheckout(UUID planId, BrandProfile brand, String ipAddress)
      throws UnsupportedEncodingException {
    Plan plan = validateCheckout(planId, brand);
    if (vnPayConfig.getHashSecret() == null || vnPayConfig.getHashSecret().isBlank())
      throw new com.influencermatch.backend.exception.BusinessException(
          com.influencermatch.backend.exception.ErrorCode.INTERNAL_ERROR,
          "Payment provider is not configured");

    List<Payment> recent =
        paymentRepository.findRecentPendingCheckouts(
            brand.getId(),
            planId,
            "VNPAY",
            LocalDateTime.now().minusMinutes(CHECKOUT_REUSE_WINDOW_MINUTES),
            org.springframework.data.domain.PageRequest.of(0, 1));
    if (!recent.isEmpty()) {
      Payment existing = recent.get(0);
      if (existing.getGatewayCreateDate() != null && existing.getTransactionCode() != null)
        return rebuildVnPayCheckoutUrl(existing, plan, ipAddress);
    }

    Subscription subscription = createPendingSubscription(brand, plan);
    String txnRef = UUID.randomUUID().toString().replace("-", "").substring(0, 15);
    Payment payment = createPendingPayment(brand, subscription, plan, "VNPAY", txnRef);
    paymentRepository.save(payment);

    Map<String, String> params = new HashMap<>();
    params.put("vnp_Version", "2.1.0");
    params.put("vnp_Command", "pay");
    params.put("vnp_TmnCode", vnPayConfig.getTmnCode());
    params.put("vnp_Amount", VnPayUtil.gatewayAmount(plan.getPrice()));
    params.put("vnp_CurrCode", "VND");
    params.put("vnp_TxnRef", txnRef);
    params.put("vnp_OrderInfo", "Thanh toan don hang " + txnRef);
    params.put("vnp_OrderType", "other");
    params.put("vnp_Locale", "vn");
    params.put("vnp_ReturnUrl", vnPayConfig.getReturnUrl());
    params.put("vnp_IpAddr", ipAddress);

    LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    payment.setGatewayCreateDate(now.format(formatter));
    params.put("vnp_CreateDate", payment.getGatewayCreateDate());
    params.put("vnp_ExpireDate", now.plusMinutes(15).format(formatter));

    String queryString = encodeVnPayParams(params);
    queryString += "&vnp_SecureHash=" + VnPayUtil.hashAllFields(params, vnPayConfig.getHashSecret());
    return new PaymentResponse(vnPayConfig.getPayUrl() + "?" + queryString, txnRef);
  }

  PaymentResponse rebuildVnPayCheckoutUrl(Payment existing, Plan plan, String ipAddress)
      throws UnsupportedEncodingException {
    String txnRef = existing.getTransactionCode();
    LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    Map<String, String> params = new HashMap<>();
    params.put("vnp_Version", "2.1.0");
    params.put("vnp_Command", "pay");
    params.put("vnp_TmnCode", vnPayConfig.getTmnCode());
    params.put("vnp_Amount", VnPayUtil.gatewayAmount(plan.getPrice()));
    params.put("vnp_CurrCode", "VND");
    params.put("vnp_TxnRef", txnRef);
    params.put("vnp_OrderInfo", "Thanh toan don hang " + txnRef);
    params.put("vnp_OrderType", "other");
    params.put("vnp_Locale", "vn");
    params.put("vnp_ReturnUrl", vnPayConfig.getReturnUrl());
    params.put("vnp_IpAddr", ipAddress);
    params.put("vnp_CreateDate", existing.getGatewayCreateDate());
    params.put(
        "vnp_ExpireDate", now.plusMinutes(CHECKOUT_REUSE_WINDOW_MINUTES).format(formatter));
    String queryString = encodeVnPayParams(params);
    queryString += "&vnp_SecureHash=" + VnPayUtil.hashAllFields(params, vnPayConfig.getHashSecret());
    log.info(
        "Reusing PENDING VNPay checkout txnRef={} for brand={}",
        txnRef,
        existing.getBrandProfile().getId());
    return new PaymentResponse(vnPayConfig.getPayUrl() + "?" + queryString, txnRef);
  }

  private String encodeVnPayParams(Map<String, String> params)
      throws UnsupportedEncodingException {
    return params.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.US_ASCII)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.US_ASCII))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  @Transactional
  public PaymentResponse createPayOsCheckout(UUID planId, BrandProfile brand) {
    Plan plan = validateCheckout(planId, brand);
    if (!payOsConfig.isConfigured())
      throw new com.influencermatch.backend.exception.BusinessException(
          com.influencermatch.backend.exception.ErrorCode.INTERNAL_ERROR,
          "Payment provider is not configured");

    List<Payment> recent =
        paymentRepository.findRecentPendingCheckouts(
            brand.getId(),
            planId,
            "PAYOS",
            LocalDateTime.now().minusMinutes(CHECKOUT_REUSE_WINDOW_MINUTES),
            org.springframework.data.domain.PageRequest.of(0, 1));
    if (!recent.isEmpty()) {
      Payment existing = recent.get(0);
      if (existing.getGatewayPaymentUrl() != null
          && !existing.getGatewayPaymentUrl().isBlank()
          && existing.getTransactionCode() != null)
        return new PaymentResponse(existing.getGatewayPaymentUrl(), existing.getTransactionCode());
    }
    long amount = payOsAmount(plan);
    Subscription subscription = createPendingSubscription(brand, plan);
    long orderCode =
        System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextLong(1000);
    String txnRef = Long.toString(orderCode);
    Payment payment = createPendingPayment(brand, subscription, plan, "PAYOS", txnRef);
    paymentRepository.save(payment);
    String description = "IM" + String.format("%07d", orderCode % 10_000_000);
    String paymentUrl = payOsPaymentClient.createPaymentLink(orderCode, amount, description);
    payment.setGatewayPaymentUrl(paymentUrl);
    paymentRepository.save(payment);
    return new PaymentResponse(paymentUrl, txnRef);
  }

  private Plan validateCheckout(UUID planId, BrandProfile brand) {
    Permissions.require(
        Permission.MANAGE_OWN_SUBSCRIPTION);
    if (!brand
        .getUser()
        .getId()
        .equals(Permissions.actor().getId()))
      throw new com.influencermatch.backend.exception.ForbiddenException(
          "Payment belongs to another brand");
    brands
        .lockForBilling(brand.getId())
        .orElseThrow(() -> new ResourceNotFoundException("Brand not found"));
    Plan plan =
        planRepository
            .findById(planId)
            .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));

    if (plan.getStatus() != com.influencermatch.backend.billing.enums.PlanStatus.ACTIVE
        || !"VND".equals(plan.getCurrency())
        || plan.getPrice().signum() <= 0)
      throw new com.influencermatch.backend.exception.ValidationException(
          "Checkout requires an active paid VND plan");
    return plan;
  }

  private Subscription createPendingSubscription(BrandProfile brand, Plan plan) {
    Subscription subscription =
        Subscription.builder()
            .brandProfile(brand)
            .plan(plan)
            .startDate(LocalDate.now())
            .expirationDate(LocalDate.now().plusDays(plan.getDurationDays()))
            .status(SubscriptionStatus.PENDING)
            .usedCampaignQuota(0)
            .usedRecommendationQuota(0)
            .usedRefreshQuota(0)
            .build();
    subscription.capturePlan();
    return subscriptionRepository.save(subscription);
  }

  private Payment createPendingPayment(
      BrandProfile brand, Subscription subscription, Plan plan, String gateway, String txnRef) {
    return Payment.builder()
        .brandProfile(brand)
        .subscription(subscription)
        .amount(plan.getPrice())
        .currency("VND")
        .paymentGateway(gateway)
        .transactionCode(txnRef)
        .status(PaymentStatus.PENDING)
        .createdAt(LocalDateTime.now())
        .build();
  }

  private long payOsAmount(Plan plan) {
    if (plan.getPrice().stripTrailingZeros().scale() > 0
        || plan.getPrice().compareTo(java.math.BigDecimal.valueOf(9_007_199_254_740_991L)) > 0) {
      throw new com.influencermatch.backend.exception.ValidationException(
          "PayOS checkout requires a whole VND amount within the supported range");
    }
    return plan.getPrice().longValueExact();
  }

  public enum CallbackResult {
    ACCEPTED,
    INVALID_SIGNATURE,
    INVALID_MERCHANT,
    NOT_FOUND,
    INVALID_AMOUNT,
    PENDING,
    CONFLICT
  }

  public enum PayOsReturnResult {
    PAID,
    FAILED,
    PENDING,
    NOT_FOUND
  }

  @Transactional
  public PayOsReturnResult processPayOsReturn(String orderCodeValue) {
    if (orderCodeValue == null || !orderCodeValue.matches("\\d{1,19}"))
      return PayOsReturnResult.NOT_FOUND;

    Payment existing =
        paymentRepository.findByTransactionCode(orderCodeValue).orElse(null);
    if (existing == null || !"PAYOS".equals(existing.getPaymentGateway()))
      return PayOsReturnResult.NOT_FOUND;
    if (existing.getStatus() == PaymentStatus.PAID) return PayOsReturnResult.PAID;
    if (existing.getStatus() == PaymentStatus.FAILED) return PayOsReturnResult.FAILED;

    var link = payOsPaymentClient.getPaymentLink(Long.parseLong(orderCodeValue));
    if (link == null
        || link.getOrderCode() == null
        || link.getOrderCode() != Long.parseLong(orderCodeValue)
        || link.getAmount() == null
        || existing.getAmount().compareTo(java.math.BigDecimal.valueOf(link.getAmount())) != 0
        || link.getAmountPaid() == null
        || link.getAmountPaid() < 0
        || link.getAmountPaid() > link.getAmount()) {
      log.warn("PayOS return query mismatch for orderCode='{}'", orderCodeValue);
      return PayOsReturnResult.PENDING;
    }

    Payment payment = settlement.lock(existing);
    if (payment.getStatus() == PaymentStatus.PAID) return PayOsReturnResult.PAID;
    if (payment.getStatus() == PaymentStatus.FAILED) return PayOsReturnResult.FAILED;

    if (link.getStatus() == vn.payos.model.v2.paymentRequests.PaymentLinkStatus.PAID
        && link.getAmountPaid().compareTo(link.getAmount()) == 0) {
      return settlement.apply(payment, true, null)
          ? PayOsReturnResult.PAID
          : PayOsReturnResult.PENDING;
    }

    if (link.getAmountPaid() == 0L
        && (link.getStatus() == vn.payos.model.v2.paymentRequests.PaymentLinkStatus.CANCELLED
            || link.getStatus() == vn.payos.model.v2.paymentRequests.PaymentLinkStatus.EXPIRED
            || link.getStatus() == vn.payos.model.v2.paymentRequests.PaymentLinkStatus.FAILED)) {
      return settlement.apply(payment, false, "PAYMENT_CANCELLED")
          ? PayOsReturnResult.FAILED
          : PayOsReturnResult.PENDING;
    }
    return PayOsReturnResult.PENDING;
  }

  @Transactional
  public CallbackResult processPayOsWebhook(String rawBody) {
    vn.payos.model.webhooks.WebhookData event;
    try {
      event = payOsPaymentClient.verifyWebhook(rawBody);
    } catch (vn.payos.exception.WebhookException exception) {
      log.warn("PayOS webhook rejected: invalid signature or payload");
      return CallbackResult.INVALID_SIGNATURE;
    }
    String orderCode = event.getOrderCode() == null ? null : event.getOrderCode().toString();
    Payment existing = paymentRepository.findByTransactionCode(orderCode).orElse(null);
    if (existing == null || !"PAYOS".equals(existing.getPaymentGateway())) {
      log.warn("PayOS webhook rejected: transaction not found for orderCode='{}'", orderCode);
      return CallbackResult.NOT_FOUND;
    }
    Payment payment = settlement.lock(existing);
    if (event.getAmount() == null
        || payment.getAmount().compareTo(java.math.BigDecimal.valueOf(event.getAmount())) != 0
        || !"VND".equals(event.getCurrency())) {
      log.warn("PayOS webhook rejected: amount or currency mismatch for orderCode='{}'", orderCode);
      return CallbackResult.INVALID_AMOUNT;
    }
    if (!"00".equals(event.getCode())) return CallbackResult.PENDING;
    return settlement.apply(payment, true, null)
        ? CallbackResult.ACCEPTED
        : CallbackResult.CONFLICT;
  }

  @Transactional
  public boolean processVnPayReturn(Map<String, String> params) {
    return processVnPayCallback(params) == CallbackResult.ACCEPTED;
  }

  @Transactional
  public CallbackResult processVnPayCallback(Map<String, String> params) {
    String signature = params.get("vnp_SecureHash");
    String txnRef = params.get("vnp_TxnRef");
    if (signature == null || signature.length() != 128) {
      log.warn("VNPay callback rejected: invalid or missing vnp_SecureHash for txnRef='{}'", txnRef);
      return CallbackResult.INVALID_SIGNATURE;
    }
    if (vnPayConfig.getHashSecret() == null || vnPayConfig.getHashSecret().isBlank()) {
      log.error("VNPay callback rejected: vnp_HashSecret is unconfigured for txnRef='{}'", txnRef);
      return CallbackResult.INVALID_SIGNATURE;
    }
    Map<String, String> signed = new HashMap<>();
    params.forEach(
        (key, value) -> {
          if (key.startsWith("vnp_")
              && !key.equals("vnp_SecureHash")
              && !key.equals("vnp_SecureHashType")) signed.put(key, value);
        });
    String expected = VnPayUtil.hashAllFields(signed, vnPayConfig.getHashSecret());
    if (!java.security.MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
      log.warn("VNPay callback rejected: signature mismatch for txnRef='{}'", txnRef);
      return CallbackResult.INVALID_SIGNATURE;
    }
    if (!Objects.equals(vnPayConfig.getTmnCode(), params.get("vnp_TmnCode"))) {
      log.warn(
          "VNPay callback rejected: merchant code mismatch for txnRef='{}', received='{}'",
          txnRef,
          params.get("vnp_TmnCode"));
      return CallbackResult.INVALID_MERCHANT;
    }
    Payment existing =
        paymentRepository.findByTransactionCode(txnRef).orElse(null);
    if (existing == null) {
      log.warn("VNPay callback rejected: transaction not found for txnRef='{}'", txnRef);
      return CallbackResult.NOT_FOUND;
    }
    Payment payment = settlement.lock(existing);
    long expectedAmount =
        payment.getAmount().multiply(java.math.BigDecimal.valueOf(100)).longValueExact();
    if (!Long.toString(expectedAmount).equals(params.get("vnp_Amount"))) {
      log.warn(
          "VNPay callback rejected: amount mismatch for txnRef='{}', expected={}, received={}",
          txnRef,
          expectedAmount,
          params.get("vnp_Amount"));
      return CallbackResult.INVALID_AMOUNT;
    }
    String transactionStatus = params.get("vnp_TransactionStatus");
    // Unknown or unfinished outcomes must remain pending for reconciliation.
    if (!java.util.Set.of("00", "02").contains(transactionStatus == null ? "" : transactionStatus))
      return CallbackResult.PENDING;
    if ("00".equals(transactionStatus) && !"00".equals(params.get("vnp_ResponseCode")))
      return CallbackResult.PENDING;
    boolean success =
        "00".equals(params.get("vnp_ResponseCode"))
            && "00".equals(params.get("vnp_TransactionStatus"));
    return settlement.apply(payment, success, params.get("vnp_ResponseCode"))
        ? CallbackResult.ACCEPTED : CallbackResult.CONFLICT;
  }
}
