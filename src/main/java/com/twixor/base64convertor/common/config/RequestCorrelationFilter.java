package com.twixor.base64convertor.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Attaches a correlation id to every request: reused from the incoming {@code X-Request-Id}
 * header when present, generated otherwise. The id (plus a request timestamp) is placed in
 * Log4j2's {@link ThreadContext} for the lifetime of the request — so every log line emitted
 * while handling it is automatically tagged, with no per-controller logging changes — and echoed
 * back as response headers on every response, including binary/file-download responses, since
 * this runs as a servlet filter rather than depending on any particular response body shape.
 *
 * <p>Runs on every request/response, business logic and DTOs untouched. See
 * docs/API_Observability_Version_Metadata_Design_Review.md for the design rationale.
 */
@Component
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String API_VERSION_HEADER = "X-API-Version";
    public static final String TIMESTAMP_HEADER = "X-Timestamp";

    public static final String MDC_REQUEST_ID_KEY = "requestId";
    public static final String REQUEST_ID_ATTRIBUTE = "requestId";

    private final ApiMetadataProperties apiMetadataProperties;

    public RequestCorrelationFilter(ApiMetadataProperties apiMetadataProperties) {
        this.apiMetadataProperties = apiMetadataProperties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String incomingRequestId = request.getHeader(REQUEST_ID_HEADER);
        String requestId = (incomingRequestId != null && !incomingRequestId.isBlank())
                ? incomingRequestId
                : UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();

        try {
            ThreadContext.put(MDC_REQUEST_ID_KEY, requestId);
            request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);

            response.setHeader(REQUEST_ID_HEADER, requestId);
            response.setHeader(API_VERSION_HEADER, apiMetadataProperties.getVersion());
            response.setHeader(TIMESTAMP_HEADER, timestamp);

            filterChain.doFilter(request, response);
        } finally {
            ThreadContext.clearAll();
        }
    }
}
