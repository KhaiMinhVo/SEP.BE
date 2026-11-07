package com.influencermatch.backend.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.auth.dto.PolicyAcceptance;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.model.User;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class RegistrationSupport {
  private final JdbcTemplate jdbc;
  private final BrandProfileRepository profiles;
  private final BrandContextRepository contexts;
  private final ObjectMapper mapper;
  private final Clock clock;

  @Value("${application.policies.terms-version:terms-v1}")
  private String terms;

  @Value("${application.policies.privacy-version:privacy-v1}")
  private String privacy;

  public Map<String, String> policies() {
    return Map.of("termsVersion", terms, "privacyVersion", privacy);
  }

  public void validate(PolicyAcceptance p) {
    if (p == null
        || !Boolean.TRUE.equals(p.acceptedTerms())
        || !Boolean.TRUE.equals(p.acceptedPrivacy()))
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "Terms and Privacy acceptance is required");
    if (!terms.equals(p.termsVersion()) || !privacy.equals(p.privacyVersion()))
      throw new BusinessException(
          ErrorCode.POLICY_VERSION_CHANGED, "Policy versions changed; load /auth/policies again");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void consent(User user, PolicyAcceptance p, String source) {
    validate(p);
    for (var entry : Map.of("TERMS", terms, "PRIVACY", privacy).entrySet())
      jdbc.update(
          "insert into"
              + " user_policy_acceptance(id,user_id,policy_type,policy_version,accepted_at,source)"
              + " values(?,?,?,?,?,?)",
          UUID.randomUUID(),
          user.getId(),
          entry.getKey(),
          entry.getValue(),
          Timestamp.from(clock.instant()),
          source);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void brand(User user, String name, String phone) {
    String normalized = phone(phone);
    if (name == null || name.isBlank() || name.trim().length() > 200)
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "businessName is required (max 200)");
    var p =
        profiles.save(
            BrandProfile.builder()
                .user(user)
                .businessName(name.trim())
                .phoneNumber(normalized)
                .build());
    var data =
        mapper
            .createObjectNode()
            .put("businessName", p.getBusinessName())
            .put("phoneNumber", normalized);
    contexts.save(
        BrandContext.builder()
            .brandProfile(p)
            .contextVersion(1)
            .profileContext(data)
            .learnedPatterns(mapper.createArrayNode())
            .evidenceRefs(mapper.createArrayNode())
            .build());
  }

  public static String phone(String raw) {
    if (raw == null)
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "phoneNumber is required");
    String p = raw.trim().replaceAll("[\\s().-]", "");
    if (p.startsWith("0") && p.matches("0[0-9]{9,10}")) p = "+84" + p.substring(1);
    if (!p.matches("\\+[1-9][0-9]{7,14}"))
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "phoneNumber must be a Vietnamese or E.164 number");
    return p;
  }
}
