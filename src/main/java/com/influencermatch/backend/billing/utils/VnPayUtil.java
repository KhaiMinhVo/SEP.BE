package com.influencermatch.backend.billing.utils;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import com.influencermatch.backend.exception.ValidationException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class VnPayUtil {
  public static String gatewayAmount(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0)
      throw new ValidationException("VNPAY refund requires a positive whole VND amount");
    String scaled = amount.multiply(BigDecimal.valueOf(100)).toBigIntegerExact().toString();
    if (scaled.length() > 12) throw new ValidationException("Amount exceeds VNPAY's 12-digit amount field");
    return scaled;
  }


  public static String hmacSHA512(final String key, final String data) {
    try {
      if (key == null || data == null) {
        throw new NullPointerException();
      }
      final Mac hmac512 = Mac.getInstance("HmacSHA512");
      byte[] hmacKeyBytes = key.getBytes(StandardCharsets.UTF_8);
      final SecretKeySpec secretKey = new SecretKeySpec(hmacKeyBytes, "HmacSHA512");
      hmac512.init(secretKey);
      byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
      byte[] result = hmac512.doFinal(dataBytes);
      StringBuilder sb = new StringBuilder(2 * result.length);
      for (byte b : result) {
        sb.append(String.format("%02x", b & 0xff));
      }
      return sb.toString();

    } catch (Exception ex) {
      return "";
    }
  }

  public static String hashAllFields(Map<String, String> fields, String secretKey) {
    List<String> fieldNames = new ArrayList<>(fields.keySet());
    Collections.sort(fieldNames);
    StringBuilder sb = new StringBuilder();
    Iterator<String> itr = fieldNames.iterator();
    while (itr.hasNext()) {
      String fieldName = (String) itr.next();
      String fieldValue = (String) fields.get(fieldName);
      if ((fieldValue != null) && (fieldValue.length() > 0)) {
        sb.append(java.net.URLEncoder.encode(fieldName, StandardCharsets.US_ASCII));
        sb.append("=");
        sb.append(java.net.URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII));
      }
      if (itr.hasNext()) {
        sb.append("&");
      }
    }
    return hmacSHA512(secretKey, sb.toString());
  }

  public static String md5(String message) {
    String digest = null;
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] hash = md.digest(message.getBytes("UTF-8"));
      StringBuilder sb = new StringBuilder(2 * hash.length);
      for (byte b : hash) {
        sb.append(String.format("%02x", b & 0xff));
      }
      digest = sb.toString();
    } catch (Exception ex) {
      digest = "";
    }
    return digest;
  }
}
