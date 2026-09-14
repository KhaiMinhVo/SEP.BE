package com.influencermatch.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Custom {@link AccessDeniedHandler} that returns a structured JSON
 * {@link ApiResponse} instead of the default HTML 403 page when an
 * authenticated but unauthorised request reaches a protected endpoint.
 *
 * <p>Spring Security invokes this handler when the caller is authenticated
 * but lacks the required authority (e.g., a {@code ROLE_BRAND} user
 * attempting to access an admin-only endpoint).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {

        log.warn("Access denied to '{}': {}", request.getRequestURI(), accessDeniedException.getMessage());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ApiResponse<Void> body = ApiResponse.error(
                "Access denied. You do not have the required permissions to perform this action.");

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
