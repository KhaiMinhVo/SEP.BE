package com.influencermatch.backend.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class CloudinaryConfig {

  @Value("${cloudinary.cloud-name:}")
  private String cloudName;

  @Value("${cloudinary.api-key:}")
  private String apiKey;

  @Value("${cloudinary.api-secret:}")
  private String apiSecret;

  @Value("${cloudinary.url:}")
  private String cloudinaryUrl;

  @Bean
  public Cloudinary cloudinary() {
    if (cloudinaryUrl != null && !cloudinaryUrl.isBlank()) {
      log.info("Initializing Cloudinary from CLOUDINARY_URL");
      return new Cloudinary(cloudinaryUrl);
    }

    if (cloudName != null && !cloudName.isBlank()
        && apiKey != null && !apiKey.isBlank()
        && apiSecret != null && !apiSecret.isBlank()) {
      log.info("Initializing Cloudinary with cloud-name: {}", cloudName);
      Map<String, String> config = ObjectUtils.asMap(
          "cloud_name", cloudName.trim(),
          "api_key", apiKey.trim(),
          "api_secret", apiSecret.trim(),
          "secure", true
      );
      return new Cloudinary(config);
    }

    log.warn("Cloudinary configuration missing: cloud-name, api-key, or api-secret is empty. Cloudinary storage will be inactive.");
    return null;
  }
}
