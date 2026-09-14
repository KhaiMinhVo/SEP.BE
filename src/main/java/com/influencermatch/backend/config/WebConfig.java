package com.influencermatch.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC web configuration for the InfluencerMatch backend.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Global CORS policy — restricts cross-origin requests to known front-end origins.</li>
 * </ul>
 *
 * <p><strong>Production note:</strong> Replace {@code allowedOriginPatterns("*")} with
 * explicit origins (e.g. {@code "https://app.influencermatch.com"}) before going live.
 * Using a wildcard is acceptable during local development only.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Configures global CORS mappings.
     *
     * <ul>
     *   <li>All API paths ({@code /**}) are covered.</li>
     *   <li>Standard HTTP methods for a REST API are permitted.</li>
     *   <li>The {@code Authorization} header is explicitly allowed so clients can
     *       send Bearer tokens in pre-flighted requests.</li>
     *   <li>{@code allowCredentials} is set to {@code true} to support
     *       cookie-based refresh token flows if introduced in the future.</li>
     *   <li>Pre-flight results are cached for 3 600 seconds (1 hour) to reduce
     *       unnecessary OPTIONS round-trips.</li>
     * </ul>
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // ⚠️  Replace with explicit origins in production:
                // .allowedOrigins("https://app.influencermatch.com")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept", "X-Requested-With")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
