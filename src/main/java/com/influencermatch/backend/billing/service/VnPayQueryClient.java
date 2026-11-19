package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.utils.VnPayUtil;
import com.influencermatch.backend.config.VnPayConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class VnPayQueryClient {
  private final VnPayConfig config;
  private final RestClient http;
  public VnPayQueryClient(VnPayConfig config) {
    this.config = config;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5000);
    factory.setReadTimeout(10000);
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  public Map<String, String> query(String txnRef, String gatewayDate) {
    return query(txnRef, gatewayDate, true);
  }

  public Map<String, String> queryRefund(String txnRef, String gatewayDate) {
    return query(txnRef, gatewayDate, false);
  }

  private Map<String, String> query(String txnRef, String gatewayDate, boolean paymentOnly) {
    Map<String, String> request = new LinkedHashMap<>();
    request.put("vnp_RequestId", UUID.randomUUID().toString().replace("-", ""));
    request.put("vnp_Version", "2.1.0");
    request.put("vnp_Command", "querydr");
    request.put("vnp_TmnCode", config.getTmnCode());
    request.put("vnp_TxnRef", txnRef);
    request.put("vnp_TransactionDate", gatewayDate);
    request.put("vnp_CreateDate", LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"))
        .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
    request.put("vnp_IpAddr", config.getQueryIpAddress());
    request.put("vnp_OrderInfo", "Query payment " + txnRef);
    request.put("vnp_SecureHash", VnPayUtil.hmacSHA512(config.getHashSecret(),
        String.join("|", request.values())));
    Map<String, String> response = http.post().uri(config.getQueryUrl())
        .contentType(MediaType.APPLICATION_JSON).body(request).retrieve()
        .body(new ParameterizedTypeReference<Map<String, String>>() {});
    if (!verified(response) || !txnRef.equals(response.get("vnp_TxnRef"))
        || !config.getTmnCode().equals(response.get("vnp_TmnCode"))
        || !"querydr".equals(response.get("vnp_Command"))
        || !"00".equals(response.get("vnp_ResponseCode"))
        || (paymentOnly && !"01".equals(response.get("vnp_TransactionType"))))
      throw new IllegalStateException("Unverified or unresolved VNPay query result");
    return response;
  }

  public boolean verified(Map<String, String> response) {
    if (response == null || config.getHashSecret() == null || config.getHashSecret().isBlank()) return false;
    String signature = response.get("vnp_SecureHash");
    if (signature == null || signature.length() != 128) return false;
    String[] fields = {"vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message",
        "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate",
        "vnp_TransactionNo", "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo"};
    String data = Arrays.stream(fields).map(k -> Objects.toString(response.get(k), ""))
        .collect(java.util.stream.Collectors.joining("|"));
    // VNPay publishes both the original and promotion-enabled query response formats.
    if (response.containsKey("vnp_PromotionCode") || response.containsKey("vnp_PromotionAmount"))
      data += "|" + Objects.toString(response.get("vnp_PromotionCode"), "")
          + "|" + Objects.toString(response.get("vnp_PromotionAmount"), "");
    String expected = VnPayUtil.hmacSHA512(config.getHashSecret(), data);
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
        signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
  }
}
