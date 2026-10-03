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

  @Transactional
  public String createPaymentUrl(UUID planId, BrandProfile brand, String ipAddress) throws UnsupportedEncodingException {
    Plan plan = planRepository.findById(planId)
        .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));

    Subscription subscription = Subscription.builder()
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

    Payment payment = Payment.builder()
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
    long amount = plan.getPrice().longValue() * 100L;
    
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

    StringBuilder hashDataBuilder = new StringBuilder();
    StringBuilder query = new StringBuilder();
    vnp_Params.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> {
          try {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value != null && value.length() > 0) {
              hashDataBuilder.append(key).append('=').append(value).append('&');
              query.append(URLEncoder.encode(key, StandardCharsets.US_ASCII.toString()))
                  .append('=')
                  .append(URLEncoder.encode(value, StandardCharsets.US_ASCII.toString()))
                  .append('&');
            }
          } catch (Exception e) {
            // ignore
          }
        });
        
    String hashString = hashDataBuilder.substring(0, hashDataBuilder.length() - 1);
    String queryString = query.substring(0, query.length() - 1);
    
    String vnp_SecureHash = VnPayUtil.hmacSHA512(vnPayConfig.getHashSecret(), hashString);
    queryString += "&vnp_SecureHash=" + vnp_SecureHash;
    
    return vnPayConfig.getPayUrl() + "?" + queryString;
  }

  @Transactional
  public boolean processVnPayReturn(Map<String, String> params) {
    String vnp_SecureHash = params.get("vnp_SecureHash");
    if (params.containsKey("vnp_SecureHashType")) {
        params.remove("vnp_SecureHashType");
    }
    if (params.containsKey("vnp_SecureHash")) {
        params.remove("vnp_SecureHash");
    }
    
    String signValue = VnPayUtil.hashAllFields(params, vnPayConfig.getHashSecret());
    if (signValue.equals(vnp_SecureHash)) {
      String txnRef = params.get("vnp_TxnRef");
      Payment payment = paymentRepository.findByTransactionCode(txnRef).orElse(null);
      if (payment != null && payment.getStatus() == PaymentStatus.PENDING) {
        if ("00".equals(params.get("vnp_ResponseCode"))) {
          payment.setStatus(PaymentStatus.PAID);
          payment.setPaidAt(LocalDateTime.now());
          
          Subscription sub = payment.getSubscription();
          sub.setStatus(SubscriptionStatus.ACTIVE);
          subscriptionRepository.save(sub);
        } else {
          payment.setStatus(PaymentStatus.FAILED);
          payment.setFailureReason(params.get("vnp_ResponseCode"));
          
          Subscription sub = payment.getSubscription();
          sub.setStatus(SubscriptionStatus.CANCELLED);
          subscriptionRepository.save(sub);
        }
        paymentRepository.save(payment);
        return true;
      }
    }
    return false;
  }
}
