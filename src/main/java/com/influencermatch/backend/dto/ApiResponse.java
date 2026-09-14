package com.influencermatch.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

/**
 * Generic, standardised HTTP response envelope for all InfluencerMatch API endpoints.
 *
 * <p>Every response — success or failure — is wrapped in this structure so that
 * consumers always have a predictable JSON contract:
 *
 * <pre>{@code
 * {
 *   "success"  : true,
 *   "message"  : "Resource retrieved successfully.",
 *   "data"     : { ... },
 *   "timestamp": "2025-01-01T00:00:00"
 * }
 * }</pre>
 *
 * <p>Use the static factory methods {@link #ok(String, T)}, {@link #ok(T)},
 * and {@link #error(String)} to construct instances without boilerplate.
 *
 * @param <T> The type of the response payload.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** Indicates whether the request was processed without errors. */
    private boolean success;

    /** Human-readable message describing the outcome of the operation. */
    private String message;

    /**
     * The actual response payload; {@code null} for error responses
     * (excluded from serialisation via {@link JsonInclude}).
     */
    private T data;

    /** Server-side timestamp at the moment the response was generated. */
    private LocalDateTime timestamp;

    // ─────────────────────────────────────────────────────────────────────────
    //  Static Factory Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a successful response with a custom message and payload.
     *
     * @param message Human-readable success message.
     * @param data    Response payload.
     * @param <T>     Payload type.
     * @return A populated {@link ApiResponse} with {@code success = true}.
     */
    public static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates a successful response with a default message and payload.
     *
     * @param data Response payload.
     * @param <T>  Payload type.
     * @return A populated {@link ApiResponse} with {@code success = true}.
     */
    public static <T> ApiResponse<T> ok(T data) {
        return ok("Operation completed successfully.", data);
    }

    /**
     * Creates a successful response with only a message (no payload).
     * Useful for operations such as DELETE that return no body.
     *
     * @param message Human-readable success message.
     * @param <T>     Payload type (usually {@link Void}).
     * @return A populated {@link ApiResponse} with {@code success = true} and {@code data = null}.
     */
    public static <T> ApiResponse<T> ok(String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates an error response with a custom message.
     *
     * @param message Human-readable error description.
     * @param <T>     Payload type (usually {@link Void}).
     * @return A populated {@link ApiResponse} with {@code success = false} and {@code data = null}.
     */
    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates an error response from an {@link HttpStatus} code and a message.
     *
     * @param status  HTTP status for contextual logging (not serialised).
     * @param message Human-readable error description.
     * @param <T>     Payload type.
     * @return A populated {@link ApiResponse} with {@code success = false}.
     */
    public static <T> ApiResponse<T> error(HttpStatus status, String message) {
        return error(message);
    }
}
