package com.influencermatch.backend.creator.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InstagramUnifiedOAuthPropertiesTest {
  static final String KEY =
      "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

  InstagramUnifiedOAuthProperties config() {
    return new InstagramUnifiedOAuthProperties(
        true,
        "976403021867954",
        "private-unified-secret",
        "1176260962020417",
        "v26.0",
        "https://www.influencermatch.site/api/v1/integrations/instagram-unified/callback",
        KEY);
  }

  @Test
  void usesSeparateUnifiedConfigIdAndNoManualScope() {
    var url = config().authorizationUrl("state");

    assertThat(url)
        .contains("client_id=976403021867954")
        .contains("config_id=1176260962020417")
        .contains("response_type=code")
        .contains("state=state")
        .contains(
            "redirect_uri=https://www.influencermatch.site/api/v1/integrations/instagram-unified/callback")
        .doesNotContain("scope=", "1681499253329405");
  }

  @Test
  void neverExposesSecretsOrEncryptionKeyInToString() {
    assertThat(config().toString())
        .doesNotContain("private-unified-secret", "1176260962020417", KEY);
  }

  @ParameterizedTest
  @ValueSource(strings = {"disabled", "key", "config", "version", "redirect"})
  void invalidOrDisabledConfigurationFailsBeforeNetworkCalls(String reason) {
    var props =
        new InstagramUnifiedOAuthProperties(
            !reason.equals("disabled"),
            "976403021867954",
            "private-unified-secret",
            reason.equals("config") ? "invalid" : "1176260962020417",
            reason.equals("version") ? "latest" : "v26.0",
            reason.equals("redirect")
                ? "http://localhost:8080/api/v1/integrations/instagram-unified/callback"
                : "https://www.influencermatch.site/api/v1/integrations/instagram-unified/callback",
            reason.equals("key") ? "short" : KEY);

    assertThatThrownBy(props::requireConfigured)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.code())
                    .isEqualTo(ErrorCode.INSTAGRAM_OAUTH_UNAVAILABLE));
  }
}
