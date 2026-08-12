package com.twixor.base64convertor.common.config;

import com.twixor.base64convertor.common.dto.ApiResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the end-to-end correlation-id contract without needing a full Spring context: once
 * {@link RequestCorrelationFilter} has populated Log4j2's {@code ThreadContext} for a request (as
 * it does for every real request via {@code doFilterInternal}), {@link ApiResponse#success} and
 * {@link ApiResponse#error} must read that same id back rather than minting a new one — this is
 * the fix applied to {@code PdfProtectionController} so the id in the response body, the
 * {@code X-Request-Id} header, and the request's log lines are always identical.
 */
class RequestCorrelationIntegrationTest {

    @Test
    void apiResponseSuccess_reusesFilterPopulatedRequestId() {
        org.apache.logging.log4j.ThreadContext.put(RequestCorrelationFilter.MDC_REQUEST_ID_KEY, "CORR-ABC");
        try {
            ApiResponse<String> response = ApiResponse.success("ok", "payload");
            assertEquals("CORR-ABC", response.getRequestId());
        } finally {
            org.apache.logging.log4j.ThreadContext.clearAll();
        }
    }

    @Test
    void apiResponseError_reusesFilterPopulatedRequestId() {
        org.apache.logging.log4j.ThreadContext.put(RequestCorrelationFilter.MDC_REQUEST_ID_KEY, "CORR-XYZ");
        try {
            ApiResponse<Object> response = ApiResponse.error("failure");
            assertEquals("CORR-XYZ", response.getRequestId());
        } finally {
            org.apache.logging.log4j.ThreadContext.clearAll();
        }
    }

    @Test
    void apiResponse_fallsBackToGeneratedUuid_whenNoFilterRan() {
        org.apache.logging.log4j.ThreadContext.clearAll();
        ApiResponse<String> response = ApiResponse.success("ok", "payload");
        assertEquals(36, response.getRequestId().length());
    }
}
