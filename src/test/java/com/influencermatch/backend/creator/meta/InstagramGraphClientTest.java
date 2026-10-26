package com.influencermatch.backend.creator.meta;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.influencermatch.backend.exception.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class InstagramGraphClientTest {
  static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
  static final String KEY = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=";

  static InstagramOAuthProperties config() {
    return new InstagramOAuthProperties(
        true,
        "789",
        "private-ig-secret",
        "v26.0",
        "https://test.example/api/v1/integrations/instagram/callback",
        KEY);
  }

  MockRestServiceServer server;
  InstagramGraphClient graph;

  @BeforeEach
  void setup() {
    var builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    graph = new InstagramGraphClient(config(), Clock.fixed(NOW, ZoneOffset.UTC), builder.build());
  }

  String token() {
    return "{\"access_token\":\"private-ig-token\",\"user_id\":\"99\",\"permissions\":\"instagram_business_basic\"}";
  }

  String profile(String type) {
    return "{\"id\":\"99\",\"user_id\":\"1784000001\",\"username\":\"only_ig\",\"account_type\":\""
        + type
        + "\"}";
  }

  void exchangeResponse(String body) {
    server
        .expect(requestTo("https://api.instagram.com/oauth/access_token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("client_id=789"),
                        org.hamcrest.Matchers.containsString("grant_type=authorization_code"),
                        org.hamcrest.Matchers.containsString("code=one-time-code"))))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
  }

  void profileResponse(String body) {
    server
        .expect(
            requestTo(
                "https://graph.instagram.com/v26.0/me?fields=id,user_id,username,account_type"))
        .andExpect(header("Authorization", "Bearer private-ig-token"))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
  }

  MetaDtos.Proof previous() {
    return new MetaDtos.Proof(
        "99",
        "private-ig-token",
        NOW.plusSeconds(100),
        Set.of("instagram_business_basic"),
        List.of(new MetaDtos.Asset("1784000001", null, "only_ig", "only_ig")));
  }

  void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.META_AUTHORIZATION_REJECTED));
    server.verify();
  }

  @ParameterizedTest
  @CsvSource({"BUSINESS,false", "MEDIA_CREATOR,true"})
  void directLoginUsesCanonicalUserIdWithoutFacebookOrPage(String type, boolean wrapped) {
    exchangeResponse(
        wrapped
            ? "{\"data\":["
                + token().replace("\"user_id\":\"99\"", "\"user_id\":\"1784000001\"")
                + "]}"
            : token());
    profileResponse(profile(type));
    var proof = graph.exchange("one-time-code");
    assertThat(proof.subject()).isEqualTo("99");
    assertThat(proof.assets())
        .containsExactly(new MetaDtos.Asset("1784000001", null, "only_ig", "only_ig"));
    assertThat(proof.tokenExpiresAt()).isEqualTo(NOW.plusSeconds(600));
    assertThat(proof.toString()).doesNotContain("private-ig-token");
    server.verify();
  }

  @Test
  void providerExpiryCanShortenButNeverExtendTheLocalProofWindow() {
    exchangeResponse(token().replace("}", ",\"expires_in\":30}"));
    profileResponse(profile("BUSINESS"));
    assertThat(graph.exchange("one-time-code").tokenExpiresAt()).isEqualTo(NOW.plusSeconds(30));
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(ints = {5, 31})
  void networkTimeNeverExtendsTheProviderExpiry(int elapsedSeconds) {
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    Clock movingClock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return reads.getAndIncrement() == 0 ? NOW : NOW.plusSeconds(elapsedSeconds);
          }
        };
    var builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    graph = new InstagramGraphClient(config(), movingClock, builder.build());
    exchangeResponse(token().replace("}", ",\"expires_in\":30}"));
    profileResponse(profile("BUSINESS"));
    if (elapsedSeconds < 30)
      assertThat(graph.exchange("one-time-code").tokenExpiresAt()).isEqualTo(NOW.plusSeconds(30));
    else rejected(() -> graph.exchange("one-time-code"));
    server.verify();
  }

  @Test
  void arrayPermissionsAndFreshVerificationRetainTheOriginalBound() {
    exchangeResponse(
        token()
            .replace(
                "\"permissions\":\"instagram_business_basic\"",
                "\"permissions\":[\"instagram_business_basic\"]"));
    profileResponse(profile("BUSINESS"));
    profileResponse(profile("BUSINESS"));
    var proof = graph.exchange("one-time-code");
    assertThat(graph.verify(proof).tokenExpiresAt()).isEqualTo(proof.tokenExpiresAt());
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "permission_missing",
        "permission_denied",
        "token_empty",
        "subject_invalid",
        "ambiguous",
        "multiple",
        "expiry_zero"
      })
  void malformedTokenResponsesFailClosedBeforeReadingAnyAccount(String reason) {
    String body =
        switch (reason) {
          case "permission_missing" ->
              token().replace(",\"permissions\":\"instagram_business_basic\"", "");
          case "permission_denied" -> token().replace("instagram_business_basic", "other");
          case "token_empty" -> token().replace("private-ig-token", "");
          case "subject_invalid" ->
              token().replace("\"user_id\":\"99\"", "\"user_id\":\"invalid\"");
          case "ambiguous" -> token().replace("}", ",\"data\":[]}");
          case "multiple" -> "{\"data\":[" + token() + "," + token() + "]}";
          default -> token().replace("}", ",\"expires_in\":0}");
        };
    exchangeResponse(body);
    rejected(() -> graph.exchange("one-time-code"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "personal",
        "missing_user_id",
        "missing_id",
        "empty_username",
        "different_subject"
      })
  void invalidOrNonProfessionalProfilesNeverBecomeProof(String reason) {
    exchangeResponse(token());
    String body = profile("BUSINESS");
    body =
        switch (reason) {
          case "personal" -> body.replace("BUSINESS", "PERSONAL");
          case "missing_user_id" -> body.replace("\"user_id\":\"1784000001\",", "");
          case "missing_id" -> body.replace("\"id\":\"99\",", "");
          case "empty_username" -> body.replace("only_ig", "");
          default -> body.replace("\"id\":\"99\"", "\"id\":\"98\"");
        };
    profileResponse(body);
    rejected(() -> graph.exchange("one-time-code"));
  }

  @Test
  void canonicalAccountChangesDuringConfirmAreRejected() {
    profileResponse(profile("BUSINESS").replace("1784000001", "1784000002"));
    rejected(() -> graph.verify(previous()));
  }

  @Test
  void expiredProofIsRejectedWithoutNetworkRequests() {
    var p = previous();
    rejected(
        () ->
            graph.verify(new MetaDtos.Proof(p.subject(), p.token(), NOW, p.scopes(), p.assets())));
  }

  @Test
  void timeoutIsSanitizedAndCodeExchangeIsNotRetried() {
    server
        .expect(requestTo("https://api.instagram.com/oauth/access_token"))
        .andRespond(
            withException(new java.net.SocketTimeoutException("private-ig-secret one-time-code")));
    assertThatThrownBy(() -> graph.exchange("one-time-code"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              assertThat(e.getMessage()).doesNotContain("private-ig-secret", "one-time-code");
              assertThat(e.getCause()).isNull();
            });
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 500})
  void httpFailuresNeverExposeTheProviderResponse(int status) {
    server
        .expect(
            requestTo(
                "https://graph.instagram.com/v26.0/me?fields=id,user_id,username,account_type"))
        .andRespond(
            withStatus(HttpStatus.valueOf(status)).body("private-ig-token private-ig-secret"));
    assertThatThrownBy(() -> graph.verify(previous()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.code())
                  .isEqualTo(
                      status == 500
                          ? ErrorCode.META_PROVIDER_ERROR
                          : ErrorCode.META_AUTHORIZATION_REJECTED);
              assertThat(e.getMessage()).doesNotContain("private-ig-token", "private-ig-secret");
              assertThat(e.getCause()).isNull();
            });
    server.verify();
  }

  @Test
  void separateEncryptionWorksWithFacebookDisabledAndCannotDecryptAcrossFlows() {
    var disabled = new MetaOAuthProperties(false, null, null, null, null, null, null);
    var cipher =
        new MetaPayloadCipher(
            disabled, config(), new ObjectMapper().registerModule(new JavaTimeModule()));
    String payload = cipher.encrypt(OAuthFlow.INSTAGRAM_LOGIN, previous());
    assertThat(payload).doesNotContain("private-ig-token");
    assertThat(cipher.decrypt(OAuthFlow.INSTAGRAM_LOGIN, payload)).isEqualTo(previous());
    assertThatThrownBy(() -> cipher.decrypt(payload)).isInstanceOf(BusinessException.class);
    assertThat(config().toString()).doesNotContain("private-ig-secret", KEY);
    assertThat(config().authorizationUrl("state"))
        .startsWith("https://www.instagram.com/oauth/authorize?")
        .contains("scope=instagram_business_basic")
        .doesNotContain("pages_show_list", "facebook.com");
  }

  @ParameterizedTest
  @ValueSource(strings = {"disabled", "key", "redirect", "version"})
  void invalidConfigurationFailsBeforeNetworkCalls(String reason) {
    var p =
        new InstagramOAuthProperties(
            !reason.equals("disabled"),
            "789",
            "secret",
            reason.equals("version") ? "latest" : "v26.0",
            reason.equals("redirect")
                ? "http://localhost:8080/api/v1/integrations/instagram/callback"
                : config().redirectUri(),
            reason.equals("key") ? "bad" : KEY);
    assertThatThrownBy(p::requireConfigured)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.INSTAGRAM_OAUTH_UNAVAILABLE));
    server.verify();
  }
}
