package com.twixor.base64convertor.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link LogSanitizer}, added per the logging-review remediation (Finding 1 —
 * embedded URL credentials not sanitized). Covers exactly the six cases named in the review,
 * plus a few defensive edge cases already handled by the implementation.
 */
class LogSanitizerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "'https://user:password@host/file.pdf', 'https://host/file.pdf'",
            "'https://user@host/file.pdf', 'https://host/file.pdf'",
            "'https://user:password@host:8443/file.pdf', 'https://host:8443/file.pdf'",
            "'https://host/file.pdf?token=abc', 'https://host/file.pdf'",
            "'https://host/file.pdf#fragment', 'https://host/file.pdf'",
            "'https://host/file.pdf?token=abc#fragment', 'https://host/file.pdf'"
    })
    void sanitizeUrl_stripsCredentialsQueryAndFragment(String input, String expected) {
        assertEquals(expected, LogSanitizer.sanitizeUrl(input));
    }

    @Test
    void sanitizeUrl_plainUrlUnchanged() {
        assertEquals("https://host/file.pdf", LogSanitizer.sanitizeUrl("https://host/file.pdf"));
    }

    @Test
    void sanitizeUrl_nullInputReturnsNull() {
        assertNull(LogSanitizer.sanitizeUrl(null));
    }

    @Test
    void sanitizeUrl_blankInputReturnedUnchanged() {
        assertEquals("", LogSanitizer.sanitizeUrl(""));
    }

    @Test
    void sanitizeUrl_credentialsWithQueryAndFragmentAllStripped() {
        assertEquals("https://host:8443/path/file.pdf",
                LogSanitizer.sanitizeUrl("https://user:password@host:8443/path/file.pdf?token=abc#frag"));
    }

    @Test
    void sanitizeUrl_malformedInputStillStripsQueryAndFragment() {
        // Raw space makes this an invalid URI, exercising the fallback path.
        String result = LogSanitizer.sanitizeUrl("not a valid uri ?token=abc123#frag");
        assertEquals("not a valid uri ", result);
    }
}
