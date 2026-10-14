package com.influencermatch.backend.creator.client;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class AiServiceClient {

  private final RestClient restClient;

  public AiServiceClient(
      @Value("${ai.service.url:http://localhost:8000/api/v1/ai/embed}") String aiServiceUrl) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    requestFactory.setReadTimeout(Duration.ofSeconds(15));

    this.restClient =
        RestClient.builder()
            .baseUrl(aiServiceUrl)
            .requestFactory(requestFactory)
            .build();
    log.info("Initialized AiServiceClient with endpoint: {}", aiServiceUrl);
  }

  @SuppressWarnings("unchecked")
  public List<Float> getEmbedding(String text) {
    try {
      Map<String, Object> response =
          restClient
              .post()
              .contentType(MediaType.APPLICATION_JSON)
              .body(Map.of("text", text))
              .retrieve()
              .body(Map.class);

      if (response != null && response.containsKey("vector")) {
        List<?> rawVector = (List<?>) response.get("vector");
        return rawVector.stream()
            .map(val -> ((Number) val).floatValue())
            .toList();
      }
    } catch (Exception e) {
      log.error("Failed to fetch embedding from Python AI Service: {}", e.getMessage());
    }
    return Collections.emptyList();
  }
}
