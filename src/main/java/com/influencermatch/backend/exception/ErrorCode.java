package com.influencermatch.backend.exception;
import org.springframework.http.HttpStatus;
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email already exists"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "Account disabled"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    OPTIMISTIC_CONFLICT(HttpStatus.CONFLICT, "Concurrent update conflict"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    private final HttpStatus status; private final String title;
    ErrorCode(HttpStatus status, String title) { this.status=status; this.title=title; }
    public HttpStatus status(){ return status; } public String title(){ return title; }
}
