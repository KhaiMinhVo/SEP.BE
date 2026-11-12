package com.influencermatch.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when the authenticated principal attempts to access a resource or perform an action for
 * which they lack the required permissions.
 *
 * <p>Maps to HTTP {@code 403 Forbidden} via {@link ResponseStatus}.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * if (!currentUser.getId().equals(resource.getOwnerId())) {
 *     throw new ForbiddenException("You do not have permission to modify this resource.");
 * }
 * }</pre>
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ForbiddenException extends BusinessException {

  /**
   * Creates a {@code ForbiddenException} with a descriptive message.
   *
   * @param message Human-readable explanation of the access restriction.
   */
  public ForbiddenException(String message) {
    super(ErrorCode.ACCESS_DENIED, message);
  }
}
