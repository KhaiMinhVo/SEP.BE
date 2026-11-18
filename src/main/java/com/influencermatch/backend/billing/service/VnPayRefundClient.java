package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.utils.VnPayUtil;
import com.influencermatch.backend.config.VnPayConfig;
import com.influencermatch.backend.exception.ValidationException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** VNPAY 2.1.0: https://sandbox.vnpayment.vn/apis/docs/truy-van-hoan-tien/querydr%26refund.html */
@Component
public class VnPayRefundClient {
  public record Submission(UUID paymentId, String requestId, String txnRef, BigDecimal amount,
      String gatewayDate, String createdBy, String createdDate) {}
  private final VnPayConfig config;
  private final VnPayQueryClient verifier;
  private final RestClient http;

  @Autowired
  public VnPayRefundClient(VnPayConfig config, VnPayQueryClient verifier) {
    this(config, verifier, builder());
  }
  public VnPayRefundClient(VnPayConfig config, VnPayQueryClient verifier, RestClient.Builder builder) {
    this.config = config; this.verifier = verifier; this.http = builder.build();
  }
  private static RestClient.Builder builder() {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5000); factory.setReadTimeout(15000);
    return RestClient.builder().requestFactory(factory);
  }
  public Map<String, String> refund(Submission s) {
    Map<String, String> request = new LinkedHashMap<>();
    request.put("vnp_RequestId", s.requestId()); request.put("vnp_Version", "2.1.0");
    request.put("vnp_Command", "refund"); request.put("vnp_TmnCode", config.getTmnCode());
    request.put("vnp_TransactionType", "02"); request.put("vnp_TxnRef", s.txnRef());
    request.put("vnp_Amount", VnPayUtil.gatewayAmount(s.amount())); request.put("vnp_TransactionNo", "");
    request.put("vnp_TransactionDate", s.gatewayDate()); request.put("vnp_CreateBy", s.createdBy());
    request.put("vnp_CreateDate", s.createdDate()); request.put("vnp_IpAddr", config.getQueryIpAddress());
    request.put("vnp_OrderInfo", "Full refund " + s.txnRef());
    request.put("vnp_SecureHash", VnPayUtil.hmacSHA512(config.getHashSecret(), String.join("|", request.values())));
    Map<String, String> response = http.post().uri(config.getQueryUrl()).contentType(MediaType.APPLICATION_JSON)
        .body(request).retrieve().body(new ParameterizedTypeReference<Map<String, String>>() {});
    if (!verifier.verified(response) || !"refund".equals(response.get("vnp_Command"))
        || !config.getTmnCode().equals(response.get("vnp_TmnCode"))
        || !s.txnRef().equals(response.get("vnp_TxnRef"))
        || !"02".equals(response.get("vnp_TransactionType"))
        || new BigDecimal(VnPayUtil.gatewayAmount(s.amount())).compareTo(new BigDecimal(response.get("vnp_Amount"))) != 0)
      throw new IllegalStateException("Unverified or mismatched VNPAY refund result");
    return response;
  }
}
