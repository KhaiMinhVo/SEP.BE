package com.influencermatch.backend.campaign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.campaign.dto.CampaignAutofillRequest;
import com.influencermatch.backend.campaign.dto.CampaignAutofillResponse;
import com.influencermatch.backend.campaign.enums.CampaignObjective;
import com.influencermatch.backend.config.GeminiProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiCampaignAutofillService {

  private final GeminiProperties geminiProperties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  // ───────────────────────────────────────────────────────────────────────────
  //  Public API
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given a short natural-language brand prompt, calls Gemini to generate a
   * fully populated CampaignAutofillResponse that the frontend can use to
   * pre-fill the campaign creation form.
   */
  public CampaignAutofillResponse autofill(CampaignAutofillRequest request) {
    String prompt = buildPrompt(request.prompt());
    String rawJson = callGemini(prompt);
    return parseResponse(rawJson);
  }

  // ───────────────────────────────────────────────────────────────────────────
  //  Prompt Engineering
  // ───────────────────────────────────────────────────────────────────────────

  private String buildPrompt(String userInput) {
    LocalDate today = LocalDate.now();
    LocalDate defaultStart = today.plusDays(7);
    LocalDate defaultEnd = today.plusDays(37);

    return """
        You are a marketing campaign assistant for an influencer marketing platform.
        A brand user has described their campaign in natural language. Your task is to
        extract and infer all relevant campaign parameters and return them as a strict
        JSON object – no markdown, no code fences, just raw JSON.

        === PLATFORM ENUM VALUES (use exactly these strings) ===
        INSTAGRAM, TIKTOK, YOUTUBE, FACEBOOK, TWITTER, THREADS

        === OBJECTIVE ENUM VALUES (use exactly these strings) ===
        AWARENESS, ENGAGEMENT, TRAFFIC, CONVERSION

        === OUTPUT FORMAT (return ONLY this JSON, nothing else) ===
        {
          "name": "string – concise campaign name (max 200 chars)",
          "productService": "string – what product/service is being promoted (max 500 chars)",
          "objective": "AWARENESS | ENGAGEMENT | TRAFFIC | CONVERSION",
          "targetAudiences": ["array of audience segments, e.g. 'Women 18-35', 'Skincare enthusiasts'"],
          "contentType": "string – e.g. 'Short video review', 'Unboxing', 'Tutorial post'",
          "startDate": "YYYY-MM-DD (default to %s if not mentioned)",
          "endDate": "YYYY-MM-DD (default to %s if not mentioned)",
          "platforms": ["array of platform enum values"],
          "niches": ["relevant niches, e.g. 'beauty', 'skincare', 'lifestyle'"],
          "locations": ["target locations, e.g. 'Vietnam', 'Ho Chi Minh City' – infer from context or use ['Vietnam'] as default"],
          "followerMin": number | null (minimum follower count, e.g. 10000),
          "followerMax": number | null (maximum follower count, e.g. 500000),
          "budgetMin": number | null (minimum budget in VND, e.g. 5000000),
          "budgetMax": number | null (maximum budget in VND, e.g. 20000000),
          "aiExplanation": "string – 2-3 sentences explaining why you made these choices"
        }

        === BUDGET INTERPRETATION GUIDELINES ===
        - "budget tầm tầm" or "budget trung bình" → budgetMin: 10000000, budgetMax: 30000000
        - "budget thấp" or "budget ít" → budgetMin: 2000000, budgetMax: 10000000
        - "budget cao" or "budget lớn" → budgetMin: 50000000, budgetMax: 200000000
        - Specific amount mentioned → use that; if only one end given, set the other proportionally

        === FOLLOWER INTERPRETATION GUIDELINES ===
        - "micro influencer" → followerMin: 10000, followerMax: 100000
        - "macro influencer" → followerMin: 100000, followerMax: 1000000
        - "mega influencer" or "KOL lớn" → followerMin: 500000, followerMax: null
        - If not mentioned, infer from budget: low budget → micro; high budget → macro

        === BRAND DESCRIPTION ===
        %s

        Remember: Return ONLY the JSON object, no other text.
        """.formatted(defaultStart, defaultEnd, userInput);
  }

  // ───────────────────────────────────────────────────────────────────────────
  //  Gemini HTTP Call
  // ───────────────────────────────────────────────────────────────────────────

  private String callGemini(String prompt) {
    if (geminiProperties.getApiKey() == null || geminiProperties.getApiKey().isBlank()) {
      throw new com.influencermatch.backend.exception.BusinessException(
          com.influencermatch.backend.exception.ErrorCode.AI_SERVICE_UNAVAILABLE,
          "Gemini API key is not configured. Please set GEMINI_API_KEY environment variable.");
    }

    String url =
        "%s/%s:generateContent?key=%s"
            .formatted(
                geminiProperties.getBaseUrl(),
                geminiProperties.getModel(),
                geminiProperties.getApiKey());

    String requestBody;
    try {
      requestBody =
          objectMapper.writeValueAsString(
              new java.util.LinkedHashMap<String, Object>() {
                {
                  put(
                      "contents",
                      List.of(
                          new java.util.LinkedHashMap<String, Object>() {
                            {
                              put(
                                  "parts",
                                  List.of(
                                      new java.util.LinkedHashMap<String, Object>() {
                                        {
                                          put("text", prompt);
                                        }
                                      }));
                            }
                          }));
                  put(
                      "generationConfig",
                      new java.util.LinkedHashMap<String, Object>() {
                        {
                          put("maxOutputTokens", Math.max(geminiProperties.getMaxOutputTokens(), 2048));
                          put("temperature", geminiProperties.getTemperature());
                          put("responseMimeType", "application/json");
                          put("thinkingConfig", Map.of("thinkingBudget", 0));
                        }
                      });
                }
              });
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize Gemini request", e);
    }

    HttpRequest httpRequest;
    try {
      httpRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();
    } catch (Exception e) {
      throw new RuntimeException("Failed to build Gemini HTTP request", e);
    }

    HttpResponse<String> response;
    try {
      response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      log.error("Gemini API call failed", e);
      throw new com.influencermatch.backend.exception.BusinessException(
          com.influencermatch.backend.exception.ErrorCode.AI_SERVICE_UNAVAILABLE,
          "Failed to reach Gemini API: " + e.getMessage());
    }

    if (response.statusCode() != 200) {
      log.error("Gemini API error: status={}, body={}", response.statusCode(), response.body());
      throw new com.influencermatch.backend.exception.BusinessException(
          com.influencermatch.backend.exception.ErrorCode.AI_SERVICE_UNAVAILABLE,
          "Gemini API returned HTTP " + response.statusCode());
    }

    // Extract the text content from Gemini response
    try {
      JsonNode root = objectMapper.readTree(response.body());
      return root.path("candidates")
          .path(0)
          .path("content")
          .path("parts")
          .path(0)
          .path("text")
          .asText();
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse Gemini API response structure", e);
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  //  Response Parsing
  // ───────────────────────────────────────────────────────────────────────────

  private CampaignAutofillResponse parseResponse(String rawJson) {
    // Strip markdown code fences if Gemini ignored the responseMimeType hint
    String cleaned = rawJson.trim();
    if (cleaned.startsWith("```")) {
      cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "").trim();
    }

    JsonNode node;
    try {
      node = objectMapper.readTree(cleaned);
    } catch (Exception e) {
      log.error("Failed to parse Gemini JSON response: {}", cleaned);
      throw new com.influencermatch.backend.exception.BusinessException(
          com.influencermatch.backend.exception.ErrorCode.AI_PARSE_ERROR,
          "AI returned an invalid JSON response. Please try again.");
    }

    return new CampaignAutofillResponse(
        text(node, "name"),
        text(node, "productService"),
        objective(node),
        strings(node, "targetAudiences"),
        text(node, "contentType"),
        date(node, "startDate"),
        date(node, "endDate"),
        platforms(node),
        strings(node, "niches"),
        strings(node, "locations"),
        longVal(node, "followerMin"),
        longVal(node, "followerMax"),
        decimal(node, "budgetMin"),
        decimal(node, "budgetMax"),
        text(node, "aiExplanation"));
  }

  // ───────────────────────────────────────────────────────────────────────────
  //  JSON helpers
  // ───────────────────────────────────────────────────────────────────────────

  private String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }

  private Long longVal(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asLong();
  }

  private BigDecimal decimal(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.decimalValue();
  }

  private LocalDate date(JsonNode node, String field) {
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) return null;
    try {
      return LocalDate.parse(v.asText());
    } catch (DateTimeParseException e) {
      log.warn("Could not parse date field '{}': {}", field, v.asText());
      return null;
    }
  }

  private CampaignObjective objective(JsonNode node) {
    JsonNode v = node.get("objective");
    if (v == null || v.isNull()) return CampaignObjective.AWARENESS;
    try {
      return CampaignObjective.valueOf(v.asText().toUpperCase());
    } catch (IllegalArgumentException e) {
      log.warn("Unknown objective '{}', defaulting to AWARENESS", v.asText());
      return CampaignObjective.AWARENESS;
    }
  }

  private List<String> strings(JsonNode node, String field) {
    JsonNode arr = node.get(field);
    if (arr == null || !arr.isArray()) return List.of();
    List<String> result = new ArrayList<>();
    for (JsonNode item : arr) {
      if (!item.isNull()) result.add(item.asText().trim());
    }
    return result;
  }

  private List<Platform> platforms(JsonNode node) {
    JsonNode arr = node.get("platforms");
    if (arr == null || !arr.isArray()) return List.of();
    List<Platform> result = new ArrayList<>();
    for (JsonNode item : arr) {
      try {
        result.add(Platform.valueOf(item.asText().toUpperCase()));
      } catch (IllegalArgumentException e) {
        log.warn("Unknown platform '{}', skipping", item.asText());
      }
    }
    return result.isEmpty() ? List.of(Platform.INSTAGRAM, Platform.TIKTOK) : result;
  }
}
