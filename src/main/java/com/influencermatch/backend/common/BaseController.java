package com.influencermatch.backend.common;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.common.dto.StandardDTOs;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * Abstract base controller providing convenience factory methods for
 * constructing standardised {@link ResponseEntity} responses.
 *
 * <p>All REST controllers in the InfluencerMatch platform should extend this
 * class to ensure a consistent response structure across all endpoints.
 *
 * <p>This class carries no Spring annotations intentionally 窶・concrete
 * subclasses are responsible for declaring {@code @RestController},
 * {@code @RequestMapping}, and any security annotations.
 */
public abstract class BaseController {

    //  Success Responses

    /**
     * Returns {@code 200 OK} with a payload and custom message.
     *
     * @param message Human-readable success message.
     * @param data    Response payload.
     * @param <T>     Payload type.
     * @return A {@link ResponseEntity} wrapping the {@link ApiResponse}.
     */
    protected <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.ok(message, data));
    }

    /**
     * Returns {@code 200 OK} with a payload and default message.
     *
     * @param data Response payload.
     * @param <T>  Payload type.
     * @return A {@link ResponseEntity} wrapping the {@link ApiResponse}.
     */
    protected <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * Returns {@code 201 Created} with the newly created resource and a message.
     *
     * @param message Human-readable creation message.
     * @param data    The created resource.
     * @param <T>     Resource type.
     * @return A {@link ResponseEntity} with HTTP status 201.
     */
    protected <T> ResponseEntity<ApiResponse<T>> created(String message, T data) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(message, data));
    }

    /**
     * Returns {@code 200 OK} with only a success message and no payload.
     * Suitable for operations such as password reset confirmation.
     *
     * @param message Human-readable success message.
     * @return A {@link ResponseEntity} with an empty-data {@link ApiResponse}.
     */
    protected ResponseEntity<ApiResponse<Void>> ok(String message) {
        return ResponseEntity.ok(ApiResponse.empty());
    }

    /**
     * Returns {@code 204 No Content}.
     * Suitable for DELETE operations where no body is required.
     *
     * @return A {@link ResponseEntity} with no body.
     */
    protected ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().build();
    }

    //  Paginated Responses

    /**
     * Wraps a {@link Page} into the standard {@link StandardDTOs.PageResponse} envelope
     * and returns {@code 200 OK}.
     *
     * @param page    The Spring Data {@link Page} result.
     * @param message Human-readable message.
     * @param <T>     Content item type.
     * @return A {@link ResponseEntity} containing the paginated response.
     */
    protected <T> ResponseEntity<ApiResponse<StandardDTOs.PageResponse<T>>> pagedOk(
            String message,
            Page<T> page) {

        StandardDTOs.PageResponse<T> pageResponse = StandardDTOs.PageResponse.<T>builder()
                .content(page.getContent())
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .build();

        return ok(message, pageResponse);
    }

    //  Pagination Helper

    /**
     * Builds a {@link Pageable} from query parameters with defensive defaults
     * and a capped page size to prevent over-fetching.
     *
     * @param page      Zero-based page index (defaults to 0 if null or negative).
     * @param size      Number of items per page (defaults to 20; capped at 100).
     * @param sortBy    Field to sort by (defaults to "createdAt" if blank).
     * @param direction Sort direction: "asc" or "desc" (defaults to "desc").
     * @return A configured {@link Pageable}.
     */
    protected Pageable buildPageable(Integer page, Integer size, String sortBy, String direction) {
        int pageNumber  = (page  != null && page  >= 0) ? page  : 0;
        int pageSize    = (size  != null && size  >  0) ? Math.min(size, 100) : 20;
        String field    = (sortBy != null && !sortBy.isBlank()) ? sortBy : "createdAt";
        Sort.Direction dir = "asc".equalsIgnoreCase(direction)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        return PageRequest.of(pageNumber, pageSize, Sort.by(dir, field));
    }
}


