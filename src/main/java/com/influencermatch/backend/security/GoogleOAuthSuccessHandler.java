package com.influencermatch.backend.security;

import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.repository.UserRepository;

import com.influencermatch.backend.config.GoogleOAuthProperties;
import com.influencermatch.backend.auth.service.GoogleAuthService;
import com.influencermatch.backend.exception.BusinessException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import java.io.IOException;

@Component @RequiredArgsConstructor
public class GoogleOAuthSuccessHandler implements AuthenticationSuccessHandler {
    private final GoogleAuthService googleAuthService;
    private final GoogleOAuthProperties properties;

    @Override public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                                   Authentication authentication) throws IOException, ServletException {
        OidcUser user = (OidcUser) authentication.getPrincipal();
        Boolean verified = user.getEmailVerified();
        try {
            String code = googleAuthService.completeGoogleLogin(user.getSubject(), user.getEmail(), user.getFullName(), Boolean.TRUE.equals(verified));
            if (request.getSession(false) != null) request.getSession(false).invalidate();
            String target = UriComponentsBuilder.fromUriString(properties.getFrontendCallbackUrl())
                    .queryParam("code", code).build().encode().toUriString();
            response.sendRedirect(target);
        } catch (BusinessException ex) {
            if (request.getSession(false) != null) request.getSession(false).invalidate();
            String target = UriComponentsBuilder.fromUriString(properties.getFrontendCallbackUrl())
                    .queryParam("error", ex.code().name()).build().encode().toUriString();
            response.sendRedirect(target);
        }
    }
}
