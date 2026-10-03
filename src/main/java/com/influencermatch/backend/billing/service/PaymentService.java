package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.enums.SubscriptionStatus;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.model.Plan;
import com.influencermatch.backend.billing.model.Subscription;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.billing.repository.PlanRepository;
import com.influencermatch.backend.billing.repository.SubscriptionRepository;
import com.influencermatch.backend.billing.utils.VnPayUtil;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.config.VnPayConfig;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

  private final PlanRepository planRepository;
  private final SubscriptionRepository subscriptionRepository;
  private final PaymentRepository paymentRepository;
  private final VnPayConfig vnPayConfig;
  private final com.influencermatch.backend.brand.repository.BrandProfileRepository brands;
  private final jakarta.persistence.EntityManager entityManager;

  @Transactional
  public String createPaymentUrl(UUID planId, BrandProfile brand, String ipAddress)
      throws UnsupportedEncodingException {
    com.influencermatch.backend.security.Permissions.require(
        com.influencermatch.backend.security.Permission.MANAGE_OWN_SUBSCRIPTION);
    if (!brand
        .getUser()
        .getId()
        .equals(com.influencermatch.backend.security.Permissions.actor().getId()))
      throw new com.influencermatch.backend.exception.ForbiddenException(
          "Payment belongs to another brand");
    brands
        .lockForBilling(brand.getId())
        .orElseThrow(() -> new ResourceNotFoundException("Brand not found"));
    if (subscriptionRepository.existsByBrandProfileIdAndStatusAndExpirationDateGreaterThanEqual(
        brand.getId(), SubscriptionStatus.ACTIVE, LocalDate.now()))
      throw new com.influencermatch.backend.exception.ConflictException(
          com.influencermatch.backend.exception.ErrorCode.SUBSCRIPTION_CONFLICT,
          "An active subscription already exists");
    Plan plan =
        planRepository
            .findById(planId)
            .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));

    if (plan.getStatus() != com.influencermatch.backend.billing.enums.PlanStatus.ACTIVE
        || !"VND".equals(plan.getCurrency())
        || plan.getPrice().signum() <= 0)
      throw new com.influencermatch.backend.exception.ValidationException(
          "Checkout requires an active paid VND plan");
    if (vnPayConfig.getHashSecret() == null || vnPayConfig.getHashSecret().isBlank())
      throw new com.influencermatch.backend.exception.BusinessException(
          com.influencermatch.backend.exception.ErrorCode.INTERNAL_ERROR,
          "Payment provider is not configured");
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
    subscription = subscriptionRepository.save(subscription);

    String txnRef = UUID.randomUUID().toString().replace("-", "").substring(0, 15);

    Payment payment =
        Payment.builder()
            .brandProfile(brand)
            .subscription(subscription)
            .amount(plan.getPrice())
            .currency("VND")
            .paymentGateway("VNPAY")
            .transactionCode(txnRef)
            .status(PaymentStatus.PENDING)
            .createdAt(LocalDateTime.now())
            .build();
    paymentRepository.save(payment);

    // Build VNPay params
    long amount = plan.getPrice().multiply(java.math.BigDecimal.valueOf(100)).longValueExact();

    Map<String, String> vnp_Params = new HashMap<>();
    vnp_Params.put("vnp_Version", "2.1.0");
    vnp_Params.put("vnp_Command", "pay");
    vnp_Params.put("vnp_TmnCode", vnPayConfig.getTmnCode());
    vnp_Params.put("vnp_Amount", String.valueOf(amount));
    vnp_Params.put("vnp_CurrCode", "VND");
    vnp_Params.put("vnp_TxnRef", txnRef);
    vnp_Params.put("vnp_OrderInfo", "Thanh toan don hang " + txnRef);
    vnp_Params.put("vnp_OrderType", "other");
    vnp_Params.put("vnp_Locale", "vn");
    vnp_Params.put("vnp_ReturnUrl", vnPayConfig.getReturnUrl());
    vnp_Params.put("vnp_IpAddr", ipAddress);

    LocalDateTime now = LocalDateTime.now();
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    vnp_Params.put("vnp_CreateDate", now.format(formatter));
    vnp_Params.put("vnp_ExpireDate", now.plusMinutes(15).format(formatter));

    String vnp_SecureHash = VnPayUtil.hashAllFields(vnp_Params, vnPayConfig.getHashSecret());
    String queryString =
        vnp_Params.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
            .collect(java.util.stream.Collectors.joining("&"));
    queryString += "&vnp_SecureHash=" + vnp_SecureHash;

    return vnPayConfig.getPayUrl() + "?" + queryString;
  }

  @Transactional
  public boolean processVnPayReturn(Map<String, String> params) {
    String signature = params.get("vnp_SecureHash");
    if (signature == null
        || signature.length() != 128
        || vnPayConfig.getHashSecret() == null
        || vnPayConfig.getHashSecret().isBlank()) return false;
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
        signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII)))
      return false;
    if (!java.util.Objects.equals(vnPayConfig.getTmnCode(), params.get("vnp_TmnCode")))
      return false;
    Payment existing =
        paymentRepository.findByTransactionCode(params.get("vnp_TxnRef")).orElse(null);
    if (existing == null) return false;
    // The same brand lock serializes free/admin activation and payment callbacks.
    brands.lockForBilling(existing.getBrandProfile().getId()).orElseThrow();
    Payment payment =
        paymentRepository.findLockedByTransactionCode(params.get("vnp_TxnRef")).orElseThrow();
    entityManager.refresh(payment);
    long expectedAmount =
        payment.getAmount().multiply(java.math.BigDecimal.valueOf(100)).longValueExact();
    if (!Long.toString(expectedAmount).equals(params.get("vnp_Amount"))) return false;
    boolean success =
        "00".equals(params.get("vnp_ResponseCode"))
            && "00".equals(params.get("vnp_TransactionStatus"));
    if (payment.getStatus() == PaymentStatus.PAID) return success;
    if (payment.getStatus() == PaymentStatus.FAILED) return !success;
    if (payment.getStatus() != PaymentStatus.PENDING) return false;
    Subscription sub = payment.getSubscription();
    entityManager.refresh(sub);
    if (sub.getStatus() != SubscriptionStatus.PENDING) return false;
    if (success) {
      if (subscriptionRepository.existsByBrandProfileIdAndStatusAndExpirationDateGreaterThanEqual(
          payment.getBrandProfile().getId(), SubscriptionStatus.ACTIVE, LocalDate.now()))
        return false;
      payment.setStatus(PaymentStatus.PAID);
      payment.setPaidAt(LocalDateTime.now());
      sub.setStatus(SubscriptionStatus.ACTIVE);
      sub.setStartDate(LocalDate.now());
      sub.setExpirationDate(LocalDate.now().plusDays(sub.getPlan().getDurationDays()));
    } else {
      payment.setStatus(PaymentStatus.FAILED);
      payment.setFailureReason(params.get("vnp_ResponseCode"));
      sub.setStatus(SubscriptionStatus.CANCELLED);
    }
    paymentRepository.save(payment);
    subscriptionRepository.save(sub);
    return true;
  }
}
