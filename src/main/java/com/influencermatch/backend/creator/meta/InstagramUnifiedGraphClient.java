package com.influencermatch.backend.creator.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.exception.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** Facebook Login for Business SUAT flow; separate from both Instagram login products. */
@Component
public class InstagramUnifiedGraphClient {
  private static final int MAX_PAGES = 20;
  private final InstagramUnifiedOAuthProperties config;
  private final Clock clock;
  private final RestClient http;

  @Autowired
  public InstagramUnifiedGraphClient(InstagramUnifiedOAuthProperties config, Clock clock) {
    this(config, clock, client());
  }

  InstagramUnifiedGraphClient(
      InstagramUnifiedOAuthProperties config, Clock clock, RestClient http) {
    this.config = config;
    this.clock = clock;
    this.http = http;
  }

  private static RestClient client() {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5000);
    factory.setReadTimeout(5000);
    return RestClient.builder().requestFactory(factory).build();
  }

  private URI endpoint(String path) {
    return UriComponentsBuilder.fromUriString(
            "https://graph.facebook.com/" + config.graphApiVersion() + path)
        .build()
        .toUri();
  }

  private JsonNode get(URI uri, String token) {
    return http.get()
        .uri(uri)
        .headers(headers -> headers.setBearerAuth(token))
        .retrieve()
        .body(JsonNode.class);
  }

  private BusinessException rejected() {
    return new BusinessException(
        ErrorCode.META_AUTHORIZATION_REJECTED,
        "Instagram Unified Login did not grant access to an Instagram asset");
  }

  private BusinessException unavailable() {
    return new BusinessException(
        ErrorCode.META_PROVIDER_ERROR,
        "Instagram Unified verification is unavailable; start a new connection if necessary");
  }

  private String numeric(JsonNode value) {
    String text = value.asText("");
    if (!text.matches("[0-9]{1,255}")) throw rejected();
    return text;
  }

  public MetaDtos.Proof exchange(String code) {
    config.requireConfigured();
    try {
      if (code == null || code.isBlank() || code.length() > 4096) throw rejected();
      var query =
          UriComponentsBuilder.fromUriString(
                  "https://graph.facebook.com/" + config.graphApiVersion() + "/oauth/access_token")
              .queryParam("client_id", config.appId())
              .queryParam("client_secret", config.appSecret())
              .queryParam("code", code)
              .build()
              .encode()
              .toUri();
      var response = http.get().uri(query).retrieve().body(JsonNode.class);
      if (response == null || !response.path("access_token").isTextual()) throw rejected();
      String token = response.path("access_token").asText();
      if (token.isBlank()) throw rejected();
      return verifyToken(token);
    } catch (BusinessException error) {
      throw error;
    } catch (RestClientResponseException error) {
      if (Set.of(400, 401, 403).contains(error.getStatusCode().value())) throw rejected();
      throw unavailable();
    } catch (Exception error) {
      throw unavailable();
    }
  }

  public MetaDtos.Proof verify(MetaDtos.Proof previous) {
    config.requireConfigured();
    try {
      if (previous == null
          || previous.subject() == null
          || previous.token() == null
          || previous.token().isBlank()
          || previous.tokenExpiresAt() == null
          || !previous.tokenExpiresAt().isAfter(clock.instant())
          || previous.assets() == null) throw rejected();
      var fresh = verifyToken(previous.token());
      if (!previous.subject().equals(fresh.subject())) throw rejected();
      return fresh;
    } catch (BusinessException error) {
      throw error;
    } catch (RestClientResponseException error) {
      if (Set.of(400, 401, 403).contains(error.getStatusCode().value())) throw rejected();
      throw unavailable();
    } catch (Exception error) {
      throw unavailable();
    }
  }

  private MetaDtos.Proof verifyToken(String token) {
    // SUAT is not a Facebook User Token. Check the issuing app and granted permission,
    // then establish its documented business context instead of assuming type=USER.
    var debugUri = UriComponentsBuilder.fromUriString(
        "https://graph.facebook.com/" + config.graphApiVersion() + "/debug_token")
        .queryParam("input_token", token).build().encode().toUri();
    var debug = get(debugUri, config.appId() + "|" + config.appSecret());
    if (debug == null || !debug.path("data").isObject()) throw rejected();
    var data = debug.path("data");
    if (!data.path("is_valid").isBoolean() || !data.path("is_valid").asBoolean()
        || !config.appId().equals(data.path("app_id").asText())
        || data.hasNonNull("error")) throw rejected();
    // Debug Token does not document an exhaustive SUAT `type` enum. Do not require
    // SYSTEM_USER to be present; reject explicitly incompatible token classes.
    if (Set.of("USER", "PAGE", "APP").contains(data.path("type").asText())) throw rejected();
    if (!data.path("scopes").isArray()) throw rejected();
    var scopes = new HashSet<String>();
    for (var scope : data.path("scopes")) {
      if (!scope.isTextual() || scope.asText().isBlank()) throw rejected();
      scopes.add(scope.asText());
    }
    if (!scopes.contains("instagram_basic")) throw rejected();
    Instant proofExpiry = clock.instant().plusSeconds(600);
    for (String field : List.of("expires_at", "data_access_expires_at")) {
      var expiry = data.path(field);
      if (!expiry.isMissingNode() && !expiry.isNull()) {
        if (!expiry.isIntegralNumber() || !expiry.canConvertToLong() || expiry.asLong() < 0) throw rejected();
        if (expiry.asLong() > 0) {
          var at = Instant.ofEpochSecond(expiry.asLong());
          if (!at.isAfter(clock.instant())) throw rejected();
          if (at.isBefore(proofExpiry)) proofExpiry = at;
        }
      }
    }
    var me = get(endpoint("/me?fields=client_business_id"), token);
    if (me == null || !me.isObject()) throw rejected();
    String systemUser = numeric(me.path("id"));
    String businessId = numeric(me.path("client_business_id"));
    if (data.hasNonNull("user_id") && !systemUser.equals(data.path("user_id").asText())) throw rejected();
    Set<String> permittedTargets = null;
    if (data.hasNonNull("granular_scopes")) {
      if (!data.path("granular_scopes").isArray()) throw rejected();
      for (var scope : data.path("granular_scopes")) {
        if (!"instagram_basic".equals(scope.path("scope").asText()) || !scope.has("target_ids")) continue;
        if (!scope.path("target_ids").isArray()) throw rejected();
        var targets = new HashSet<String>();
        for (var target : scope.path("target_ids")) targets.add(numeric(target));
        if (permittedTargets == null) permittedTargets = targets;
        else permittedTargets.retainAll(targets);
      }
    }
    return new MetaDtos.Proof(
        businessId + ":" + systemUser,
        token,
        proofExpiry,
        Set.copyOf(scopes),
        instagramAssets(token, permittedTargets));
  }

  private List<MetaDtos.Asset> instagramAssets(String token, Set<String> permittedTargets) {
    var assets = new LinkedHashMap<String, MetaDtos.Asset>();
    var cursors = new HashSet<String>();
    String after = null;
    for (int page = 0; page < MAX_PAGES; page++) {
      var builder =
          UriComponentsBuilder.fromUriString(
                  "https://graph.facebook.com/"
                      + config.graphApiVersion()
                      + "/me/assigned_instagram_accounts")
              .queryParam("fields", "id,username")
              .queryParam("limit", 100);
      if (after != null) builder.queryParam("after", after);
      var response = get(builder.build().encode().toUri(), token);
      if (response == null || !response.path("data").isArray()) throw unavailable();
      for (var item : response.path("data")) {
        String id = item.path("id").asText("");
        if (!id.matches("[0-9]{1,255}")) continue;
        if (permittedTargets != null && !permittedTargets.contains(id)) continue;
        String username = item.path("username").asText("");
        String name = username;
        assets.putIfAbsent(
            id,
            new MetaDtos.Asset(
                id,
                null,
                name,
                username.isBlank() ? null : username,
                Set.of()));
      }
      if (response.path("paging").path("next").asText().isBlank())
        return List.copyOf(assets.values());
      after = response.path("paging").path("cursors").path("after").asText("");
      if (after.isBlank() || after.length() > 2048 || !cursors.add(after)) throw unavailable();
    }
    throw unavailable();
  }
}
