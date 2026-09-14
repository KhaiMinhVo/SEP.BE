package com.influencermatch.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SpringDoc OpenAPI 3 configuration for the InfluencerMatch REST API.
 *
 * <p>Exposes interactive Swagger UI documentation at:
 * <ul>
 *   <li>UI: {@code http://localhost:8080/api/v1/swagger-ui.html}</li>
 *   <li>JSON: {@code http://localhost:8080/api/v1/api-docs}</li>
 * </ul>
 *
 * <p>A global {@code BearerAuth} security scheme is applied so that all
 * endpoints that require authentication display the lock icon in Swagger UI,
 * and the "Authorize" button allows testers to supply a JWT once and have it
 * sent with every subsequent request.
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI influencerMatchOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort + "/api/v1")
                                .description("Local Development Server"),
                        new Server()
                                .url("https://api.influencermatch.com/api/v1")
                                .description("Production Server")
                ))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, bearerAuthScheme())
                );
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Info apiInfo() {
        return new Info()
                .title("InfluencerMatch API")
                .version("1.0.0")
                .description("""
                        REST API for the InfluencerMatch platform.
                        
                        Enables Brand/SME users to discover creators, manage campaigns, 
                        track performance, and handle end-to-end collaboration workflows.
                        
                        **Authentication**: All endpoints (except `/auth/**`) require a 
                        valid JWT Bearer token obtained via the `/auth/login` endpoint.
                        """)
                .contact(new Contact()
                        .name("InfluencerMatch Engineering")
                        .email("engineering@influencermatch.com")
                        .url("https://influencermatch.com"))
                .license(new License()
                        .name("Proprietary")
                        .url("https://influencermatch.com/terms"));
    }

    private SecurityScheme bearerAuthScheme() {
        return new SecurityScheme()
                .name(SECURITY_SCHEME_NAME)
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Provide a valid JWT access token obtained from the /auth/login endpoint.");
    }
}
