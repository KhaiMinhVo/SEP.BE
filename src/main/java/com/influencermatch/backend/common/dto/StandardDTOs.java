package com.influencermatch.backend.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Collection of standard request and response DTOs used across
 * the InfluencerMatch API surface.
 *
 * <p>DTOs in this file are deliberately kept as simple value-carrying records
 * with Lombok-generated boilerplate. Domain-specific DTOs should live in
 * feature-specific sub-packages (e.g. {@code dto.auth}, {@code dto.campaign}).
 */
public final class StandardDTOs {

    // Prevent instantiation of the container class.
    private StandardDTOs() {}

    //  Auth DTOs

    /**
     * Incoming payload for the login endpoint.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LoginRequest {
        private String email;
        private String password;
    }

    /**
     * Response returned after successful authentication.
     * Carries the access and refresh tokens together with basic profile info.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AuthResponse {
        private String accessToken;
        private String refreshToken;
        private String tokenType;
        private Long expiresIn;          // seconds until access token expiry
        private UserSummaryResponse user;
    }

    /**
     * Incoming payload for token refresh.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RefreshTokenRequest {
        private String refreshToken;
    }

    //  User DTOs

    /**
     * Lightweight user projection returned in list views and embedded payloads.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UserSummaryResponse {
        private UUID id;
        private String email;
        private String fullName;
        private String role;
        private LocalDateTime createdAt;
    }

    //  Pagination DTOs

    /**
     * Standardised paginated list wrapper returned by collection endpoints.
     *
     * @param <T> The type of items in the page.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PageResponse<T> {
        private java.util.List<T> content;
        private int pageNumber;
        private int pageSize;
        private long totalElements;
        private int totalPages;
        private boolean first;
        private boolean last;
    }

    //  Validation Error DTO

    /**
     * Structured payload returned when Bean Validation constraints are violated.
     * Contains a map of {@code field -> error message} for easy client-side form binding.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValidationErrorResponse {
        private boolean success;
        private String message;
        private Map<String, String> errors;
        private LocalDateTime timestamp;
    }
}


