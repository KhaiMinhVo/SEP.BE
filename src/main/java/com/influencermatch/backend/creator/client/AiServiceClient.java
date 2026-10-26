package com.influencermatch.backend.creator.client;

import com.influencermatch.backend.recommendation.dto.ExplainRequestDto;
import com.influencermatch.backend.recommendation.dto.ExplainResponseDto;
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

    String base = aiServiceUrl != null ? aiServiceUrl.trim() : "http://localhost:8000/api/v1/ai";
    if (base.endsWith("/embed")) {
      base = base.substring(0, base.length() - "/embed".length());
    } else if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }

    this.restClient =
        RestClient.builder()
            .baseUrl(base)
            .requestFactory(requestFactory)
            .build();
    log.info("Initialized AiServiceClient with base endpoint: {}", base);
  }

  @SuppressWarnings("unchecked")
  public List<Float> getEmbedding(String text) {
    try {
      Map<String, Object> response =
          restClient
              .post()
              .uri("/embed")
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

  /**
   * XAI Engine Integration (POST /api/v1/ai/explain) kèm Fallback Circuit Breaker.
   */
  public ExplainResponseDto getExplanation(ExplainRequestDto request) {
    try {
      ExplainResponseDto response =
          restClient
              .post()
              .uri("/explain")
              .contentType(MediaType.APPLICATION_JSON)
              .body(request)
              .retrieve()
              .body(ExplainResponseDto.class);

      if (response != null && response.getSummary() != null) {
        response.setExplanationAvailable(true);
        return response;
      }
    } catch (Exception e) {
      log.warn("Python AI Service /explain unavailable or timed out: {}. Using fallback card.", e.getMessage());
    }

    // Circuit Breaker Fallback: Trả về thẻ mặc định với explanationAvailable = false, không tạo bằng chứng giả
    return ExplainResponseDto.builder()
        .status("fallback")
        .summary("Ứng viên được đề xuất dựa trên mô hình xếp hạng Four-Memory.")
        .reasons(List.of(
            "Quy mô người theo dõi và tỷ lệ tương tác phù hợp với mục tiêu đề ra",
            "Nội dung và danh mục hoạt động tương thích với tiêu chí của chiến dịch"
        ))
        .explanationAvailable(false)
        .build();
  }
}
