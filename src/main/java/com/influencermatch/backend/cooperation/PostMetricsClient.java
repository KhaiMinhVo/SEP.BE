package com.influencermatch.backend.cooperation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Fixed upstream endpoint; never accepts a client-supplied upstream host. */
@Component
public class PostMetricsClient {
  private final RestClient client;

  public PostMetricsClient(@Value("${crawler.service.url:http://localhost:8000/api/v1/crawl}") String base) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(20));
    client = RestClient.builder().baseUrl(base).requestFactory(factory).build();
  }

  public JsonNode collect(String url) {
    return client.post().uri("/post-metrics").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("post_url", url)).retrieve().body(JsonNode.class);
  }
}
