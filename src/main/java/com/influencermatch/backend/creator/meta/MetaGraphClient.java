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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Fixed Graph origin; never follow a provider paging.next URL or expose provider error bodies. */
@Component
public class MetaGraphClient {
  private final MetaOAuthProperties config;
  private final Clock clock;
  private final RestClient http;

  @Autowired
  public MetaGraphClient(MetaOAuthProperties config, Clock clock) {
    this(config, clock, client());
  }

  MetaGraphClient(MetaOAuthProperties config, Clock clock, RestClient http) {
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

  private UriComponentsBuilder endpoint(String path) {
    return UriComponentsBuilder.fromUriString(
        "https://graph.facebook.com/" + config.graphApiVersion() + path);
  }

  private JsonNode get(URI uri, String token) {
    return http.get().uri(uri).headers(h -> h.setBearerAuth(token)).retrieve().body(JsonNode.class);
  }

  private BusinessException rejected() {
    return new BusinessException(
        ErrorCode.META_AUTHORIZATION_REJECTED, "Meta did not grant the required access");
  }

  private BusinessException failed() {
    return new BusinessException(
        ErrorCode.META_PROVIDER_ERROR,
        "Meta verification is unavailable; start a new connection if necessary");
  }

  public MetaDtos.Proof exchange(String code, String platform) {
    config.requireConfigured();
    try {
      if (code == null || code.isBlank() || code.length() > 4096) throw rejected();
      var body = new LinkedMultiValueMap<String, String>();
      body.add("client_id", config.appId());
      body.add("client_secret", config.appSecret());
      body.add("redirect_uri", config.redirectUri());
      body.add("code", code);
      var response =
          http.post()
              .uri(endpoint("/oauth/access_token").build().toUri())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(body)
              .retrieve()
              .body(JsonNode.class);
      if (response == null
          || !response.path("access_token").isTextual()
          || response.path("access_token").asText().isBlank()) throw rejected();
      return verify(response.path("access_token").asText(), platform);
    } catch (BusinessException error) {
      throw error;
    } catch (Exception error) {
      throw failed();
    }
  }

  public MetaDtos.Proof verify(String token, String platform) {
    config.requireConfigured();
    try {
      var debug =
          get(
              endpoint("/debug_token").queryParam("input_token", token).build().encode().toUri(),
              config.appId() + "|" + config.appSecret());
      if (debug == null) throw rejected();
      var data = debug.path("data");
      String subject = data.path("user_id").asText();
      long expiry = data.path("expires_at").asLong(0);
      long dataExpiry = data.path("data_access_expires_at").asLong(0);
      if (dataExpiry > 0) expiry = Math.min(expiry, dataExpiry);
      if (!data.path("is_valid").asBoolean()
          || !config.appId().equals(data.path("app_id").asText())
          || !"USER".equals(data.path("type").asText())
          || subject.isBlank()
          || expiry <= clock.instant().getEpochSecond()) throw rejected();
      var debugScopes = new HashSet<String>();
      data.path("scopes").forEach(s -> debugScopes.add(s.asText()));
      if (!debugScopes.containsAll(config.requiredScopes(platform))) throw rejected();
      var me = get(endpoint("/me").queryParam("fields", "id").build().toUri(), token);
      if (me == null || !subject.equals(me.path("id").asText())) throw rejected();
      var permissions = get(endpoint("/me/permissions").build().toUri(), token);
      var scopes = new HashSet<String>();
      if (permissions == null || !permissions.path("data").isArray()) throw rejected();
      permissions
          .path("data")
          .forEach(
              p -> {
                if ("granted".equals(p.path("status").asText()))
                  scopes.add(p.path("permission").asText());
              });
      if (!scopes.containsAll(config.requiredScopes(platform))) throw rejected();
      var assets = new LinkedHashMap<String, MetaDtos.Asset>();
      var cursors = new HashSet<String>();
      String after = null;
      for (int page = 0; page < 20; page++) {
        String fields =
            platform.equals("INSTAGRAM")
                ? "id,name,tasks,instagram_business_account{id,username,name}"
                : "id,name,tasks";
        var uri = endpoint("/me/accounts").queryParam("fields", fields).queryParam("limit", 100);
        if (after != null) uri.queryParam("after", after);
        var result = get(uri.build().encode().toUri(), token);
        if (result == null || !result.path("data").isArray()) throw failed();
        for (var item : result.path("data")) {
          String pageId = item.path("id").asText();
          if (!pageId.matches("[0-9]{1,255}")
              || !item.path("tasks").isArray()
              || item.path("tasks").isEmpty()) continue;
          var account =
              platform.equals("INSTAGRAM") ? item.path("instagram_business_account") : item;
          String id = account.path("id").asText();
          if (!id.matches("[0-9]{1,255}")) continue;
          assets.putIfAbsent(
              id,
              new MetaDtos.Asset(
                  id,
                  pageId,
                  account.path("name").asText(""),
                  account.path("username").asText("")));
        }
        if (result.path("paging").path("next").asText().isBlank())
          return new MetaDtos.Proof(
              subject,
              token,
              Instant.ofEpochSecond(expiry),
              Set.copyOf(scopes),
              List.copyOf(assets.values()));
        after = result.path("paging").path("cursors").path("after").asText();
        if (after.isBlank() || after.length() > 2048 || !cursors.add(after)) throw failed();
      }
      throw failed();
    } catch (BusinessException error) {
      throw error;
    } catch (Exception error) {
      throw failed();
    }
  }
}
