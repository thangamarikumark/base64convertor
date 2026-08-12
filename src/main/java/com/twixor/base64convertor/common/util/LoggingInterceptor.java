package com.twixor.base64convertor.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.IOException;

public class LoggingInterceptor implements ClientHttpRequestInterceptor {

    // Named to match the dedicated "com.twixor.base64convertor.http" logger/appender already
    // configured in log4j2-spring.xml, so these logs are routed to logs/http.log consistently
    // with PdfService's HTTP logging (logging remediation, Phase 4).
    private static final Logger httpLogger = LoggerFactory.getLogger("com.twixor.base64convertor.http");

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {

        logRequest(request, body);
        ClientHttpResponse response = execution.execute(request, body);
        logResponse(response);
        return response;
    }

    /**
     * Logs request metadata only — method, sanitized URL, masked headers, and payload
     * presence/size. Never logs request body content (logging remediation, CRITICAL #1).
     */
    private void logRequest(HttpRequest request, byte[] body) {
        httpLogger.debug("===== HTTP Request =====");
        httpLogger.debug("Method   : {}", request.getMethod());
        httpLogger.debug("URL      : {}", LogSanitizer.sanitizeUrl(request.getURI().toString()));
        httpLogger.debug("Headers  : {}", LogSanitizer.maskHeaders(request.getHeaders()));
        httpLogger.debug("PayloadPresent: {}, PayloadSize: {} bytes",
                body != null && body.length > 0, body != null ? body.length : 0);
    }

    /**
     * Logs response metadata only — status, masked headers, and payload presence/size. Never
     * logs response body content (logging remediation, CRITICAL #1).
     *
     * <p>The extra read of the response body performed here (solely to measure its size) is
     * gated behind {@code isDebugEnabled()}: this logger is DEBUG-only, so in every environment
     * where DEBUG is not active (UAT/staging/production), this method now does no I/O and
     * allocates nothing, rather than unconditionally buffering the full response body into a
     * throwaway byte array on every single outbound call regardless of level (logging review
     * remediation — proven risk, see docs/logging-review-remediation-report.md). This does not
     * change what any caller of the underlying RestTemplate ultimately reads: the response
     * stream is independently, fully buffered by BufferingClientHttpRequestFactory for the
     * actual business logic (e.g. byte[].class conversion) regardless of what this interceptor
     * does — only this method's own redundant extra copy is now skipped when unused.
     */
    private void logResponse(ClientHttpResponse response) throws IOException {
        if (!httpLogger.isDebugEnabled()) {
            return;
        }
        byte[] bodyBytes = StreamUtils.copyToByteArray(response.getBody());
        httpLogger.debug("===== HTTP Response =====");
        httpLogger.debug("Status   : {}", response.getStatusCode());
        httpLogger.debug("Headers  : {}", LogSanitizer.maskHeaders(response.getHeaders()));
        httpLogger.debug("PayloadPresent: {}, PayloadSize: {} bytes",
                bodyBytes.length > 0, bodyBytes.length);
    }
}
