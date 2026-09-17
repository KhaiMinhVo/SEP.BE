package com.influencermatch.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a requested resource cannot be found in the system.
 *
 * <p>Maps to HTTP {@code 404 Not Found} via {@link ResponseStatus}.
 * The {@link com.influencermatch.backend.exception.GlobalExceptionHandler}
 * intercepts this exception and serialises it into the standard
 * {@link com.influencermatch.backend.common.dto.ApiResponse} envelope.
 *
 * <p>Usage example:
 * <pre>{@code
 *   User user = userRepository.findById(id)
 *       .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
 * }</pre>
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends BusinessException {

    /**
     * Creates a {@code ResourceNotFoundException} with a fully composed,
     * developer-friendly message.
     *
     * @param resourceName The name of the resource type (e.g. "User", "Campaign").
     * @param fieldName    The name of the identifier field (e.g. "id", "email").
     * @param fieldValue   The value that was searched for.
     */
    public ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue) {
        super(ErrorCode.RESOURCE_NOT_FOUND, String.format("%s not found with %s : '%s'", resourceName, fieldName, fieldValue));
    }

    /**
     * Creates a {@code ResourceNotFoundException} with a custom, pre-composed message.
     *
     * @param message Custom error message.
     */
    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message);
    }
}


