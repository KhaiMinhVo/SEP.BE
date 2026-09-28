package com.influencermatch.backend.security;

import com.influencermatch.backend.config.GoogleOAuthProperties;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import java.io.IOException;

@Component @RequiredArgsConstructor
public class GoogleOAuthFailureHandler implements AuthenticationFailureHandler {
    private final GoogleOAuthProperties properties;
    @Override public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                                   AuthenticationException exception) throws IOException {
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        String target = UriComponentsBuilder.fromUriString(properties.getFrontendCallbackUrl())
                .queryParam("error", "GOOGLE_AUTHENTICATION_FAILED").build().encode().toUriString();
        response.sendRedirect(target);
    }
}
