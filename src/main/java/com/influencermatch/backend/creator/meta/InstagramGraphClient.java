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
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** Direct Instagram Login only. Never accepts client tokens or calls Facebook endpoints. */
@Component
public class InstagramGraphClient {
  private final InstagramOAuthProperties config;
  private final Clock clock;
  private final RestClient http;

  @Autowired
  public InstagramGraphClient(InstagramOAuthProperties config, Clock clock) {
    this(config, clock, client());
  }

  InstagramGraphClient(InstagramOAuthProperties config, Clock clock, RestClient http) {
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

  private BusinessException rejected() {
    return new BusinessException(
        ErrorCode.META_AUTHORIZATION_REJECTED,
        "Instagram did not grant a valid Professional account authorization");
  }

  private BusinessException failed() {
    return new BusinessException(
        ErrorCode.META_PROVIDER_ERROR,
        "Instagram verification is unavailable; start again if necessary");
  }

  private String numeric(JsonNode value) {
    String text = value.asText("");
    if (!text.matches("[0-9]{1,255}")) throw rejected();
    return text;
  }

  private Set<String> permissions(JsonNode value) {
    var result = new HashSet<String>();
    if (value.isTextual())
      Arrays.stream(value.asText().split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .forEach(result::add);
    else if (value.isArray()) {
      for (var item : value) {
        if (!item.isTextual() || item.asText().isBlank()) throw rejected();
        result.add(item.asText().trim());
      }
    } else throw rejected();
    if (!result.contains("instagram_business_basic")) throw rejected();
    return Set.copyOf(result);
  }

  private JsonNode account(String token) {
    URI uri =
        UriComponentsBuilder.fromUriString(
                "https://graph.instagram.com/" + config.graphApiVersion() + "/me")
            .queryParam("fields", "id,user_id,username,account_type")
            .build()
            .toUri();
    var response =
        http.get().uri(uri).headers(h -> h.setBearerAuth(token)).retrieve().body(JsonNode.class);
    if (response == null || !response.isObject()) throw rejected();
    numeric(response.path("id"));
    numeric(response.path("user_id"));
    if (!response.path("username").isTextual()
        || response.path("username").asText().isBlank()
        || !Set.of("BUSINESS", "MEDIA_CREATOR").contains(response.path("account_type").asText()))
      throw rejected();
    return response;
  }

  private MetaDtos.Proof proof(
      String token, JsonNode account, Instant validUntil, Set<String> scopes) {
    return new MetaDtos.Proof(
        numeric(account.path("id")),
        token,
        validUntil,
        scopes,
        List.of(
            new MetaDtos.Asset(
                numeric(account.path("user_id")),
                null,
                account.path("username").asText(),
                account.path("username").asText())));
  }

  public MetaDtos.Proof exchange(String code) {
    config.requireConfigured();
    try {
      if (code == null || code.isBlank() || code.length() > 4096) throw rejected();
      Instant issuedBound = clock.instant();
      var body = new LinkedMultiValueMap<String, String>();
      body.add("client_id", config.appId());
      body.add("client_secret", config.appSecret());
      body.add("grant_type", "authorization_code");
      body.add("redirect_uri", config.redirectUri());
      body.add("code", code);
      var response =
          http.post()
              .uri("https://api.instagram.com/oauth/access_token")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(body)
              .retrieve()
              .body(JsonNode.class);
      if (response == null || !response.isObject()) throw rejected();
      if (response.has("data")) {
        if (response.has("access_token")
            || !response.path("data").isArray()
            || response.path("data").size() != 1) throw rejected();
        response = response.path("data").get(0);
      }
      String token = response.path("access_token").asText("");
      if (token.isBlank()) throw rejected();
      String exchangeId = numeric(response.path("user_id"));
      var scopes = permissions(response.path("permissions"));
      long validitySeconds = 600; // local proof bound, not an invented provider lifetime
      if (response.has("expires_in")) {
        String lifetime = response.path("expires_in").asText();
        if (!lifetime.matches("[0-9]+")) throw rejected();
        long seconds = Long.parseLong(lifetime);
        if (seconds <= 0) throw rejected();
        validitySeconds = Math.min(validitySeconds, seconds);
      }
      var account = account(token);
      if (!exchangeId.equals(numeric(account.path("id")))
          && !exchangeId.equals(numeric(account.path("user_id")))) throw rejected();
      Instant validUntil = issuedBound.plusSeconds(validitySeconds);
      if (!validUntil.isAfter(clock.instant())) throw rejected();
      return proof(token, account, validUntil, scopes);
    } catch (BusinessException error) {
      throw error;
    } catch (RestClientResponseException error) {
      if (Set.of(400, 401, 403).contains(error.getStatusCode().value())) throw rejected();
      throw failed();
    } catch (Exception error) {
      throw failed();
    }
  }

  public MetaDtos.Proof verify(MetaDtos.Proof previous) {
    config.requireConfigured();
    try {
      if (!previous.tokenExpiresAt().isAfter(clock.instant())
          || !previous.scopes().contains("instagram_business_basic")
          || previous.assets().size() != 1) throw rejected();
      var account = account(previous.token());
      if (!previous.subject().equals(numeric(account.path("id")))
          || !previous.assets().getFirst().id().equals(numeric(account.path("user_id"))))
        throw rejected();
      return proof(previous.token(), account, previous.tokenExpiresAt(), previous.scopes());
    } catch (BusinessException error) {
      throw error;
    } catch (RestClientResponseException error) {
      if (Set.of(400, 401, 403).contains(error.getStatusCode().value())) throw rejected();
      throw failed();
    } catch (Exception error) {
      throw failed();
    }
  }
}
