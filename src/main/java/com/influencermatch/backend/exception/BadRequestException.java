package com.influencermatch.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when the client submits a request that violates business rules or contains logically
 * invalid data that passes Bean Validation constraints.
 *
 * <p>Maps to HTTP {@code 400 Bad Request} via {@link ResponseStatus}.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * if (userRepository.existsByEmail(request.getEmail())) {
 *     throw new BadRequestException("An account with this email already exists.");
 * }
 * }</pre>
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BadRequestException extends BusinessException {

  /**
   * Creates a {@code BadRequestException} with a descriptive message that will be surfaced to the
   * API consumer.
   *
   * @param message Human-readable explanation of why the request is invalid.
   */
  public BadRequestException(String message) {
    super(ErrorCode.VALIDATION_ERROR, message);
  }

  /**
   * Creates a {@code BadRequestException} wrapping a lower-level cause.
   *
   * @param message Human-readable explanation of why the request is invalid.
   * @param cause The original exception that triggered this error.
   */
  public BadRequestException(String message, Throwable cause) {
    super(ErrorCode.VALIDATION_ERROR, message, cause);
  }
}
