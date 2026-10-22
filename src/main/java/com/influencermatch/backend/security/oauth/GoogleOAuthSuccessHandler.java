package com.influencermatch.backend.security.oauth;

import com.influencermatch.backend.auth.service.GoogleAuthService;
import com.influencermatch.backend.config.GoogleOAuthProperties;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.user.enums.Role;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class GoogleOAuthSuccessHandler implements AuthenticationSuccessHandler {
  private final GoogleAuthService googleAuthService;
  private final GoogleOAuthProperties properties;

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    OidcUser user = (OidcUser) authentication.getPrincipal();
    Boolean verified = user.getEmailVerified();
    try {
      Object intent = request.getAttribute(GoogleLoginIntent.ATTRIBUTE);
      if (!("BRAND".equals(intent) || "CREATOR".equals(intent)))
        throw new BusinessException(
            ErrorCode.GOOGLE_AUTHENTICATION_FAILED, "Missing state-bound Google login intent");
      String code =
          googleAuthService.completeGoogleLogin(
              user.getSubject(),
              user.getEmail(),
              user.getFullName(),
              Boolean.TRUE.equals(verified),
              Role.valueOf(intent.toString()));
      if (request.getSession(false) != null) request.getSession(false).invalidate();
      String target =
          UriComponentsBuilder.fromUriString(properties.getFrontendCallbackUrl())
              .queryParam("code", code)
              .build()
              .encode()
              .toUriString();
      response.sendRedirect(target);
    } catch (BusinessException ex) {
      if (request.getSession(false) != null) request.getSession(false).invalidate();
      String target =
          UriComponentsBuilder.fromUriString(properties.getFrontendCallbackUrl())
              .queryParam("error", ex.code().name())
              .build()
              .encode()
              .toUriString();
      response.sendRedirect(target);
    }
  }
}
