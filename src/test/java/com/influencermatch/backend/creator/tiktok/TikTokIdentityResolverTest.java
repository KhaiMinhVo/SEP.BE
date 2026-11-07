package com.influencermatch.backend.creator.tiktok;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.influencermatch.backend.creator.client.CrawlerServiceClient;
import com.influencermatch.backend.creator.client.provider.TikTokSandboxProofProvider.VerifiedAccount;
import com.influencermatch.backend.creator.dto.CrawlerResolveAuthorResponse;
import com.influencermatch.backend.exception.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TikTokIdentityResolverTest {
  final Instant now = Instant.parse("2026-11-04T12:00:00Z");
  final CrawlerServiceClient crawler = mock(CrawlerServiceClient.class);
  final TikTokIdentityResolver resolver =
      new TikTokIdentityResolver(crawler, Clock.fixed(now, ZoneOffset.UTC));
  final VerifiedAccount proof = new VerifiedAccount("app-subject", " @MiXi ", "Display");

  CrawlerResolveAuthorResponse response() {
    return CrawlerResolveAuthorResponse.builder()
        .status("success")
        .platform("TIKTOK")
        .authorId("6631682124809076737")
        .idType("TIKTOK_USER_ID")
        .resolvedUsername("mixi")
        .observedAt(now)
        .build();
  }

  void reject(CrawlerResolveAuthorResponse result) {
    when(crawler.resolveTikTokIdentity("mixi")).thenReturn(result);
    assertThatThrownBy(() -> resolver.resolve(proof))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.TIKTOK_IDENTITY_RESOLUTION_FAILED));
  }

  @Test
  void acceptsFreshCanonicalIdAndUsesOnlyOAuthUsername() {
    when(crawler.resolveTikTokIdentity("mixi")).thenReturn(response());
    var result = resolver.resolve(proof);
    assertThat(result.authorId()).isEqualTo("6631682124809076737");
    assertThat(result.username()).isEqualTo("mixi");
    verify(crawler).resolveTikTokIdentity("mixi");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "0", "-1", "123x", "000123", "123456789012345678901", "secUid"})
  void rejectsInvalidAuthorIds(String id) {
    var r = response();
    r.setAuthorId(id);
    reject(r);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"null", "status", "platform", "type", "username", "time", "stale", "future"})
  void rejectsUntrustedContractEvenWhenIsMatchedTrue(String field) {
    var r = response();
    r.setIsMatched(true);
    switch (field) {
      case "null" -> {
        reject(null);
        return;
      }
      case "status" -> r.setStatus("error");
      case "platform" -> r.setPlatform("INSTAGRAM");
      case "type" -> r.setIdType("SEC_UID");
      case "username" -> r.setResolvedUsername("someone_else");
      case "time" -> r.setObservedAt(null);
      case "stale" -> r.setObservedAt(now.minusSeconds(90).minusNanos(1));
      case "future" -> r.setObservedAt(now.plusSeconds(5).plusNanos(1));
    }
    reject(r);
  }

  @ParameterizedTest
  @ValueSource(longs = {-90, 5})
  void freshnessBoundariesAreInclusive(long offset) {
    var r = response();
    r.setObservedAt(now.plusSeconds(offset));
    when(crawler.resolveTikTokIdentity("mixi")).thenReturn(r);
    assertThat(resolver.resolve(proof)).isNotNull();
  }

  @Test
  void dropsUnsafeOrOversizedOptionalProfileData() {
    var r = response();
    var info = new CrawlerResolveAuthorResponse.CreatorInfo();
    info.setAvatarUrl("javascript:private-value");
    info.setDisplayName("x".repeat(256));
    r.setCreatorInfo(info);
    when(crawler.resolveTikTokIdentity("mixi")).thenReturn(r);
    var result = resolver.resolve(proof);
    assertThat(result.avatarUrl()).isNull();
    assertThat(result.displayName()).isNull();
  }

  @Test
  void httpRequestNeverSendsExpectedIdAndNeverUpdatesStaging() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("/tiktok/resolve-author"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("{\"username\":\"mixi\",\"auto_update_staging\":false}", true))
        .andRespond(withSuccess("{\"author_id\":\"123\"}", MediaType.APPLICATION_JSON));
    assertThat(
            new CrawlerServiceClient(builder.build()).resolveTikTokIdentity("mixi").getAuthorId())
        .isEqualTo("123");
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"http", "timeout", "json"})
  void httpErrorsAreSanitizedWithoutRetry(String failure) {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    var expectation = server.expect(requestTo("/tiktok/resolve-author"));
    switch (failure) {
      case "http" -> expectation.andRespond(withServerError().body("private-response"));
      case "timeout" ->
          expectation.andRespond(
              withException(new java.net.SocketTimeoutException("private-timeout")));
      case "json" ->
          expectation.andRespond(withSuccess("not-json-private", MediaType.APPLICATION_JSON));
    }
    assertThatThrownBy(
            () -> new CrawlerServiceClient(builder.build()).resolveTikTokIdentity("mixi"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.TIKTOK_IDENTITY_RESOLUTION_FAILED))
        .hasMessageNotContaining("private");
    server.verify();
  }
}
