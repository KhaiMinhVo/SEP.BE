package com.influencermatch.backend.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
  CLAIM_EMAIL_NOT_RETRYABLE(HttpStatus.CONFLICT, "Claim email cannot be retried"),
  CLAIM_EMAIL_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Claim email retry limit exceeded"),
  CREATOR_INVITATION_PENDING(HttpStatus.CONFLICT, "A pending invitation already exists"),
  SOCIAL_PROOF_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Social provider verification unavailable"),
  EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email is not verified"),
  EMAIL_ALREADY_VERIFIED(HttpStatus.CONFLICT, "Email is already verified"),
  EMAIL_OTP_INVALID_OR_EXPIRED(HttpStatus.BAD_REQUEST, "OTP is invalid or expired"),
  EMAIL_OTP_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Email verification limit exceeded"),
  EMAIL_DELIVERY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Email delivery unavailable"),
  CREATOR_INVITATION_INVALID(HttpStatus.BAD_REQUEST, "Invitation is invalid, expired or already used"),
  DATA_QUALITY_REVIEW_CONFLICT(HttpStatus.CONFLICT, "Snapshot is no longer pending quality review"),
  CREATOR_CLAIM_CONFLICT(HttpStatus.CONFLICT, "Creator claim conflict"),
  CREATOR_ALREADY_LINKED(HttpStatus.CONFLICT, "Creator account has already been claimed and linked"),
  IDENTITY_MISMATCH(HttpStatus.CONFLICT, "Authenticated identity does not match target creator account"),
  INVITATION_ALREADY_PROCESSED(HttpStatus.CONFLICT, "Invitation has already been processed"),
  INVITATION_EXPIRED(HttpStatus.CONFLICT, "Invitation has expired"),
  CREATOR_ROLE_CHANGE_CONFLICT(HttpStatus.CONFLICT, "Creator has active ownership or pending claims"),
  COLLABORATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "Collaboration already exists"),
  OUTCOME_ALREADY_EXISTS(HttpStatus.CONFLICT, "Outcome already exists"),
  CREATOR_REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "Creator review already exists"),
  INVALID_COLLABORATION_STATE(HttpStatus.CONFLICT, "Collaboration workflow conflict"),
  LAST_ACTIVE_ADMIN(HttpStatus.CONFLICT, "The last active administrator cannot be removed"),
  BRAND_ROLE_CHANGE_CONFLICT(
      HttpStatus.CONFLICT, "Brand profile prevents internal role assignment"),
  INGESTION_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "Creator ingestion is not configured"),
  PAID_PLAN_REQUIRES_PAYMENT(HttpStatus.CONFLICT, "Paid subscription requires checkout"),
  SUBSCRIPTION_CONFLICT(HttpStatus.CONFLICT, "An active subscription already exists"),
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
  EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email already exists"),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
  ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "Account disabled"),
  ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED, "Account locked"),
  ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
  RESOURCE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Resource already exists"),
  BRAND_NOT_FOUND(HttpStatus.NOT_FOUND, "Brand not found"),
  BRAND_ALREADY_EXISTS(HttpStatus.CONFLICT, "Brand already exists"),
  CAMPAIGN_NOT_FOUND(HttpStatus.NOT_FOUND, "Campaign not found"),
  CAMPAIGN_NOT_EDITABLE(HttpStatus.CONFLICT, "Campaign is not editable"),
  INVALID_CAMPAIGN_TRANSITION(HttpStatus.CONFLICT, "Invalid campaign status transition"),
  CAMPAIGN_CONTEXT_NOT_READY(HttpStatus.CONFLICT, "Campaign context is not ready"),
  INVALID_CAMPAIGN_RANGE(HttpStatus.BAD_REQUEST, "Invalid campaign range"),
  USER_NOT_BRAND(HttpStatus.BAD_REQUEST, "User is not a brand account"),
  OPTIMISTIC_CONFLICT(HttpStatus.CONFLICT, "Concurrent update conflict"),
  QUOTA_EXCEEDED(HttpStatus.CONFLICT, "Campaign quota exceeded for current subscription plan"),
  SUBSCRIPTION_REQUIRED(HttpStatus.FORBIDDEN, "Active subscription required"),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
  AI_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI service is unavailable"),
  AI_PARSE_ERROR(HttpStatus.BAD_GATEWAY, "AI returned an unprocessable response"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
  private final HttpStatus status;
  private final String title;

  ErrorCode(HttpStatus status, String title) {
    this.status = status;
    this.title = title;
  }

  public HttpStatus status() {
    return status;
  }

  public String title() {
    return title;
  }
}
