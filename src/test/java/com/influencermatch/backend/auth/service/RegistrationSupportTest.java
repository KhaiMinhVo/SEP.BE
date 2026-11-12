package com.influencermatch.backend.auth.service;

import static org.assertj.core.api.Assertions.*;

import com.influencermatch.backend.auth.dto.PolicyAcceptance;
import com.influencermatch.backend.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.util.ReflectionTestUtils;

class RegistrationSupportTest {
  RegistrationSupport service;

  @BeforeEach
  void setup() {
    service =
        new RegistrationSupport(
            null,
            null,
            null,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            java.time.Clock.systemUTC());
    ReflectionTestUtils.setField(service, "terms", "terms-v1");
    ReflectionTestUtils.setField(service, "privacy", "privacy-v1");
  }

  @ParameterizedTest
  @CsvSource({
    "0912345678,+84912345678",
    "+84912345678,+84912345678",
    "+14155552671,+14155552671",
    "090 123 4567,+84901234567"
  })
  void normalizesPhone(String input, String expected) {
    assertThat(RegistrationSupport.phone(input)).isEqualTo(expected);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"abc", "123", "+0123456789", "+1234567890123456"})
  void invalidPhone(String value) {
    assertThatThrownBy(() -> RegistrationSupport.phone(value))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void acceptsOnlyActiveVersions() {
    service.validate(new PolicyAcceptance(true, "terms-v1", true, "privacy-v1"));
    assertThatThrownBy(
            () -> service.validate(new PolicyAcceptance(true, "old", true, "privacy-v1")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.POLICY_VERSION_CHANGED));
  }

  @Test
  void rejectsMissingOrFalseConsent() {
    assertThatThrownBy(() -> service.validate(null)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> service.validate(new PolicyAcceptance(false, "terms-v1", true, "privacy-v1")))
        .isInstanceOf(BusinessException.class);
  }
}
