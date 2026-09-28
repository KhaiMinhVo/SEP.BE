package com.influencermatch.backend.exception;
import org.springframework.http.HttpStatus;
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email already exists"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "Account disabled"),
    ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED, "Account locked"),
    GOOGLE_AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "Google authentication failed"),
    GOOGLE_EMAIL_NOT_VERIFIED(HttpStatus.UNAUTHORIZED, "Google email is not verified"),
    AUTH_CODE_INVALID_OR_EXPIRED(HttpStatus.UNAUTHORIZED, "Authentication code is invalid or expired"),
    GOOGLE_OAUTH_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "Google authentication is unavailable"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    BRAND_NOT_FOUND(HttpStatus.NOT_FOUND, "Brand not found"),
    BRAND_ALREADY_EXISTS(HttpStatus.CONFLICT, "Brand already exists"),
    CAMPAIGN_NOT_FOUND(HttpStatus.NOT_FOUND, "Campaign not found"),
    CAMPAIGN_NOT_EDITABLE(HttpStatus.CONFLICT, "Campaign is not editable"),
    INVALID_CAMPAIGN_TRANSITION(HttpStatus.CONFLICT, "Invalid campaign status transition"),
    CAMPAIGN_CONTEXT_NOT_READY(HttpStatus.CONFLICT, "Campaign context is not ready"),
    INVALID_CAMPAIGN_RANGE(HttpStatus.BAD_REQUEST, "Invalid campaign range"),
    USER_NOT_BRAND(HttpStatus.BAD_REQUEST, "User is not a brand account"),
    OPTIMISTIC_CONFLICT(HttpStatus.CONFLICT, "Concurrent update conflict"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    private final HttpStatus status; private final String title;
    ErrorCode(HttpStatus status, String title) { this.status=status; this.title=title; }
    public HttpStatus status(){ return status; } public String title(){ return title; }
}
