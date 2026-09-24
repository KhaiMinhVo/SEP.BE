package com.influencermatch.backend.billing.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

  public static String hmacSHA512(final String key, final String data) {
    try {
      if (key == null || data == null) {
        throw new NullPointerException();
      }
      final Mac hmac512 = Mac.getInstance("HmacSHA512");
      byte[] hmacKeyBytes = key.getBytes();
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
        sb.append(fieldName);
        sb.append("=");
        sb.append(fieldValue);
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
