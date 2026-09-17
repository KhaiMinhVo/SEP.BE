package com.influencermatch.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ProblemDetail;
import org.slf4j.MDC;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * Custom {@link AuthenticationEntryPoint} that returns a structured JSON
 * {@link ApiResponse} instead of the default HTML error page when an
 * unauthenticated request reaches a secured endpoint.
 *
 * <p>Spring Security invokes this entry point when:
 * <ul>
 *   <li>No {@code Authorization} header is present on a protected route.</li>
 *   <li>The JWT is invalid, expired, or tampered with.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        log.warn("Unauthorized access attempt to '{}': {}", request.getRequestURI(), authException.getMessage());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,"Authentication required. Please provide a valid Bearer token.");
        body.setTitle("Authentication required"); body.setType(URI.create("https://api.influencermatch/errors/unauthenticated"));body.setInstance(URI.create(request.getRequestURI())); body.setProperty("code","UNAUTHENTICATED"); body.setProperty("traceId",MDC.get("traceId"));body.setProperty("timestamp",Instant.now());

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}


