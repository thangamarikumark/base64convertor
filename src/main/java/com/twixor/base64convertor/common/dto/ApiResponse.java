package com.twixor.base64convertor.common.dto;

import com.twixor.base64convertor.common.config.RequestCorrelationFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.logging.log4j.ThreadContext;

import java.time.Instant;
import java.util.UUID;

/**
 * Standard response envelope: {@code status}/{@code message}/{@code data}/{@code timestamp}/
 * {@code requestId}. Adopted for {@code POST /api/files/pdf/protect} per explicit request; not
 * (yet) applied to any other endpoint — see docs/API_Response_Contract_Analysis.md for why a
 * codebase-wide migration is a separate, per-endpoint decision rather than a blanket change.
 *
 * @param <T> the endpoint-specific success payload type ({@code null} on failure)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Standard API response envelope.")
public class ApiResponse<T> {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @Schema(example = "SUCCESS", allowableValues = {"SUCCESS", "FAILED"})
    private String status;

    @Schema(example = "Operation completed successfully")
    private String message;

    @Schema(description = "Endpoint-specific payload. Null on failure.")
    private T data;

    @Schema(description = "ISO-8601 timestamp of when this response was generated.",
            example = "2026-07-02T11:05:00.123Z")
    private String timestamp;

    @Schema(description = "Correlation id for this request, for support/log lookup.",
            example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private String requestId;

    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .status(STATUS_SUCCESS)
                .message(message)
                .data(data)
                .timestamp(Instant.now().toString())
                .requestId(currentRequestId())
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .status(STATUS_FAILED)
                .message(message)
                .data(null)
                .timestamp(Instant.now().toString())
                .requestId(currentRequestId())
                .build();
    }

    /**
     * Reuses the correlation id {@link RequestCorrelationFilter} placed in {@link ThreadContext}
     * for this request, so the id in the response body matches the {@code X-Request-Id} header
     * and every log line emitted while handling the request. Falls back to a fresh UUID only if
     * the filter didn't run (e.g. a unit test constructing this DTO directly, outside the servlet
     * filter chain).
     */
    private static String currentRequestId() {
        String requestId = ThreadContext.get(RequestCorrelationFilter.MDC_REQUEST_ID_KEY);
        return (requestId != null && !requestId.isBlank()) ? requestId : UUID.randomUUID().toString();
    }
}
