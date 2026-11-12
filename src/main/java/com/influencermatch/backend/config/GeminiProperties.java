package com.influencermatch.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gemini")
@Getter
@Setter
public class GeminiProperties {

  /** Gemini API key loaded from env var GEMINI_API_KEY */
  private String apiKey = "";

  /** Model to use, e.g. gemini-2.0-flash or gemini-1.5-flash */
  private String model = "gemini-2.0-flash";

  /** Base URL for Gemini REST API */
  private String baseUrl = "https://generativelanguage.googleapis.com/v1beta/models";

  /** Max tokens for AI response */
  private int maxOutputTokens = 1024;

  /** Temperature for generation (0.0 = deterministic, 1.0 = creative) */
  private double temperature = 0.3;
}
