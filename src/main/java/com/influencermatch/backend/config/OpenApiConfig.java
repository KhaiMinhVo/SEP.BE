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
        .servers(
            List.of(
                new Server()
                    .url("/api/v1")
                    .description("Current Host Server (Inherits HTTPS/HTTP)"),
                new Server()
                    .url("https://api.influencermatch.site/api/v1")
                    .description("HTTPS Production Server")))
        .info(apiInfo())
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

**Security**: Brand registration, invited Creator registration, OTP verification, password setup, login and refresh are public. Optional Google Identity Services login is Brand-only and requires a one-time nonce and original browser cookie. Existing password accounts are not automatically linked by email. Registration requires SMTP; channel ownership is a separate Claim flow.
Authentication endpoints permit standard login. Session rotation (/auth/refresh) and logout require a CSRF header obtained from GET /auth/csrf. Refresh tokens are cookie-only.
New Google Brands complete registration before any account/session is created.
`/auth/me` and logout-all require a live session-bound JWT; logout uses the refresh cookie and CSRF, even when access JWT expired.
Sessions expire seven days after login; rotation never extends them. Permissions come from the current database role;
role/status changes invalidate existing JWT and refresh tokens immediately.
BRAND owns its business resources. ADMIN can read support data, not write Brand
profiles or campaigns. DATA_MANAGER cannot access private Brand or billing data.
Ingestion uses X-Service-Key instead of JWT. VNPay callbacks require a valid gateway
signature, matching merchant and transaction amount; they do not use user permissions.
Brand channel invitations queue encrypted email jobs before async SMTP; HTTP 200 means QUEUED. Owner Brands can inspect delivery/retry FAILED jobs. Duplicate/owned accounts return 409. Ownership transfer resolution is unavailable in MVP.
Facebook Page and linked Instagram Professional verification use real Meta OAuth when configured; no Meta mock fallback. Only existing stable official account IDs can be linked. Start in a browser to receive the HttpOnly binding cookie, complete OAuth in that browser, then inspect/confirm the private session with Creator JWT. The public Meta callback returns session ID/status only and never grants ownership. Manual Claim remains available; other simulated provider paths remain dev-only.
Direct Instagram Login also supports Business/Creator accounts without Facebook or a Page. It uses separate Instagram app configuration and `/integrations/instagram/callback`, then the same private session/confirm APIs. Session `oauthFlow` identifies the login method; direct accounts have no `pageId`. This is channel verification, not InfluencerMatch account sign-in.
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
              && !method.equals("logoutAll")
          || controller == com.influencermatch.backend.billing.controller.PaymentController.class
              && method.startsWith("vnpay")
          || controller == com.influencermatch.backend.creator.meta.MetaOAuthController.class
              && method.equals("callback")
          || controller == com.influencermatch.backend.creator.tiktok.TikTokOAuthController.class
              && method.equals("callback")
          || controller == com.influencermatch.backend.creator.meta.InstagramOAuthController.class
              && method.equals("callback")) operation.setSecurity(List.of());
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
