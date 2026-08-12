package com.twixor.base64convertor.common.config;

import jakarta.servlet.FilterChain;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link RequestCorrelationFilter} covering the scenarios called out in the
 * observability implementation task: incoming header reuse, generation when absent, MDC
 * population during the request, MDC cleanup after, and the three response headers being set
 * regardless of what the downstream handler does with the response body (byte[]/JSON/etc. are
 * irrelevant to this filter — it never touches the body).
 */
class RequestCorrelationFilterTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ApiMetadataProperties apiMetadataProperties = new ApiMetadataProperties();
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter(apiMetadataProperties);

    @AfterEach
    void clearThreadContextBetweenTests() {
        // Defensive: guarantees no leakage into other tests even if a test fails before the
        // filter's own finally block runs.
        ThreadContext.clearAll();
    }

    @Test
    void incomingRequestIdHeader_isReusedVerbatim_noNewUuidGenerated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/pdf/protect");
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "TEST-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals("TEST-123", response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER));
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingRequestIdHeader_generatesUuid_returnedInResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/pdf/protect");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        assertNotNull(generated);
        assertTrue(UUID_PATTERN.matcher(generated).matches(),
                "generated request id should be a UUID, was: " + generated);
    }

    @Test
    void blankIncomingRequestIdHeader_isTreatedAsAbsent_generatesUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/pdf/protect");
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        assertTrue(UUID_PATTERN.matcher(generated).matches());
    }

    @Test
    void mdcIsPopulated_duringRequestProcessing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/pdf/protect");
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "MDC-CHECK");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, resp) -> {
            assertEquals("MDC-CHECK", ThreadContext.get(RequestCorrelationFilter.MDC_REQUEST_ID_KEY));
            assertEquals("MDC-CHECK", req.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE));
        };

        filter.doFilter(request, response, chain);
    }

    @Test
    void mdcIsCleared_afterRequestCompletes_noLeakageBetweenRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/pdf/protect");
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "SHOULD-NOT-LEAK");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertNull(ThreadContext.get(RequestCorrelationFilter.MDC_REQUEST_ID_KEY));
    }

    @Test
    void mdcIsCleared_evenWhenDownstreamThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/pdf/protect");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) -> {
            throw new RuntimeException("downstream failure");
        };

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> filter.doFilter(request, response, chain));

        assertNull(ThreadContext.get(RequestCorrelationFilter.MDC_REQUEST_ID_KEY));
    }

    @Test
    void apiVersionHeader_reflectsConfiguredValue() throws Exception {
        apiMetadataProperties.setVersion("9.9.9");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/pdf/protect");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals("9.9.9", response.getHeader(RequestCorrelationFilter.API_VERSION_HEADER));
    }

    @Test
    void timestampHeader_isPresentAndParsableAsInstant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/pdf/protect");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String timestamp = response.getHeader(RequestCorrelationFilter.TIMESTAMP_HEADER);
        assertNotNull(timestamp);
        assertNotNull(java.time.Instant.parse(timestamp));
    }

    @Test
    void allThreeHeaders_presentOnBinaryFileDownloadStyleResponse() throws Exception {
        // Simulates a file-download endpoint: the filter never inspects/touches the response
        // body, so headers must be present the same way regardless of what the downstream
        // handler eventually writes (bytes, a Resource, a plain string, etc.).
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/download/report.pdf");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) -> {
            resp.setContentType("application/pdf");
            resp.getOutputStream().write(new byte[]{0x25, 0x50, 0x44, 0x46});
        };

        filter.doFilter(request, response, chain);

        assertNotNull(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER));
        assertNotNull(response.getHeader(RequestCorrelationFilter.API_VERSION_HEADER));
        assertNotNull(response.getHeader(RequestCorrelationFilter.TIMESTAMP_HEADER));
    }

    @Test
    void generatedRequestIdIsUnique_acrossSuccessiveRequests() throws Exception {
        MockHttpServletRequest request1 = new MockHttpServletRequest("GET", "/api/files/pdf/protect");
        MockHttpServletResponse response1 = new MockHttpServletResponse();
        filter.doFilter(request1, response1, mock(FilterChain.class));

        MockHttpServletRequest request2 = new MockHttpServletRequest("GET", "/api/files/pdf/protect");
        MockHttpServletResponse response2 = new MockHttpServletResponse();
        filter.doFilter(request2, response2, mock(FilterChain.class));

        assertNotNull(UUID.fromString(response1.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)));
        assertNotNull(UUID.fromString(response2.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)));
        assertTrue(!response1.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)
                .equals(response2.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)));
    }
}
