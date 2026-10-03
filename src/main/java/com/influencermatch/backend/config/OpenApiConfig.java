package com.influencermatch.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 3 configuration for the InfluencerMatch REST API.
 *
 * <p>Exposes interactive Swagger UI documentation at:
 *
 * <ul>
 *   <li>UI: {@code http://localhost:8080/api/v1/swagger-ui.html}
 *   <li>JSON: {@code http://localhost:8080/api/v1/api-docs}
 * </ul>
 *
 * <p>A global {@code BearerAuth} security scheme is applied so that all endpoints that require
 * authentication display the lock icon in Swagger UI, and the "Authorize" button allows testers to
 * supply a JWT once and have it sent with every subsequent request.
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
        .servers(
            List.of(
                new Server()
                    .url("http://localhost:" + serverPort + "/api/v1")
                    .description("Local Development Server"),
                new Server()
                    .url("https://api.influencermatch.com/api/v1")
                    .description("Production Server")))
        .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
        .components(
            new Components()
                .addSecuritySchemes(SECURITY_SCHEME_NAME, bearerAuthScheme())
                .addSecuritySchemes(
                    "CreatorServiceKey",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-Service-Key")
                        .description(
                            "Service-only ingestion key; does not authorize any other API.")));
  }

  //  Private helpers

  private Info apiInfo() {
    return new Info()
        .title("InfluencerMatch API")
        .version("1.0.0")
        .description(
            """
            REST API for the InfluencerMatch platform.

            Enables Brand/SME users to discover creators, manage campaigns,
            track performance, and handle end-to-end collaboration workflows.

            **Security**: Register, login, refresh and Google handshake/exchange are public.
            `/auth/me` and logout require JWT. Permissions come from the current database role;
            role/status changes invalidate existing JWT and refresh tokens immediately.
            BRAND owns its business resources. ADMIN can read support data, not write Brand
            profiles or campaigns. DATA_MANAGER cannot access private Brand or billing data.
            Ingestion uses X-Service-Key instead of JWT. VNPay callbacks require a valid gateway
            signature, matching merchant and transaction amount; they do not use user permissions.
            Notification, Recommendation and CRM permissions are declarations, not new APIs.
            """)
        .contact(
            new Contact()
                .name("InfluencerMatch Engineering")
                .email("engineering@influencermatch.com")
                .url("https://influencermatch.com"))
        .license(new License().name("Proprietary").url("https://influencermatch.com/terms"));
  }

  private SecurityScheme bearerAuthScheme() {
    return new SecurityScheme()
        .name(SECURITY_SCHEME_NAME)
        .type(SecurityScheme.Type.HTTP)
        .scheme("bearer")
        .bearerFormat("JWT")
        .description("Provide a valid JWT access token obtained from the /auth/login endpoint.");
  }

  @Bean
  public org.springdoc.core.customizers.OperationCustomizer permissionDocumentation() {
    return (operation, handler) -> {
      Class<?> controller = handler.getBeanType();
      String method = handler.getMethod().getName();
      if (controller == com.influencermatch.backend.auth.controller.AuthController.class
              && !method.equals("me")
              && !method.equals("logout")
          || controller == com.influencermatch.backend.billing.controller.PaymentController.class
              && method.startsWith("vnpay")) operation.setSecurity(List.of());
      if (controller
          == com.influencermatch.backend.creator.controller.CreatorIngestionController.class)
        operation.setSecurity(List.of(new SecurityRequirement().addList("CreatorServiceKey")));
      var permission =
          handler.getMethodAnnotation(
              org.springframework.security.access.prepost.PreAuthorize.class);
      if (permission == null)
        permission =
            org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                handler.getBeanType(),
                org.springframework.security.access.prepost.PreAuthorize.class);
      if (permission != null) {
        String description =
            operation.getDescription() == null ? "" : operation.getDescription() + "\n\n";
        operation.setDescription(
            description
                + "Required permission: `"
                + permission.value()
                + "`. Resource ownership and workflow checks are also enforced by the service.");
      }
      return operation;
    };
  }
}
