package com.twixor.base64convertor.common.util;

import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Shared logging-safety helpers (logging remediation, Phase 3/4). Used everywhere a URL or an
 * HTTP header set is written to a log line, so URLs never leak query-string tokens and
 * sensitive headers are masked consistently across the codebase.
 */
public final class LogSanitizer {

    /** Header names (case-insensitive) whose values are always replaced with **** in logs. */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token"
    );

    private static final String MASK = "****MASKED****";

    private LogSanitizer() {
    }

    /**
     * Strips embedded user-info credentials, the query string, and the fragment from a URL
     * before logging, keeping only scheme+host+port+path. Query strings can carry
     * tokens/signatures and a URL's user-info component can carry a literal
     * {@code username:password@} — the path is sufficient for operational troubleshooting.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code https://host/path/file.pdf?token=abc123&key=xyz} -&gt; {@code https://host/path/file.pdf}</li>
     *   <li>{@code https://user:password@host/file.pdf} -&gt; {@code https://host/file.pdf}</li>
     *   <li>{@code https://user:password@host:8443/file.pdf} -&gt; {@code https://host:8443/file.pdf}</li>
     * </ul>
     *
     * @param url the raw URL (may be null/blank/malformed)
     * @return the sanitized URL, or a best-effort query/fragment-stripped input if it cannot be
     *         parsed as a URI with a resolvable host (see class-level note on this residual case)
     */
    public static String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) {
                // No parseable host (e.g. an opaque/relative URI) — user-info, if any, cannot be
                // reliably isolated from the rest of the authority, so fall back to the same
                // best-effort query/fragment stripping used for malformed input below rather
                // than risk reconstructing a URI that still contains it.
                return stripQueryAndFragment(url);
            }
            String authority = (uri.getPort() != -1) ? host + ":" + uri.getPort() : host;
            URI stripped = new URI(uri.getScheme(), authority, uri.getPath(), null, null);
            return stripped.toString();
        } catch (URISyntaxException e) {
            return stripQueryAndFragment(url);
        }
    }

    /** Best-effort fallback: strip everything from the first '?' or '#' onward, whichever is first. */
    private static String stripQueryAndFragment(String url) {
        int qIndex = url.indexOf('?');
        int fIndex = url.indexOf('#');
        int cut = (qIndex < 0) ? fIndex : (fIndex < 0 ? qIndex : Math.min(qIndex, fIndex));
        return cut >= 0 ? url.substring(0, cut) : url;
    }

    /**
     * Truncates a string to at most {@code maxLength} characters for safe, bounded DEBUG-level
     * logging (e.g. an error response body), appending a "(truncated)" marker when cut.
     */
    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated)";
    }

    /**
     * Returns a copy of {@code headers} with the value of every sensitive header (Authorization,
     * Cookie, Set-Cookie, X-API-Key, X-Auth-Token) replaced by a fixed mask. Non-sensitive
     * headers are copied through unchanged. This is the single shared masking implementation
     * used by every HTTP-logging call site in the application.
     */
    public static HttpHeaders maskHeaders(HttpHeaders headers) {
        HttpHeaders masked = new HttpHeaders();
        if (headers == null) {
            return masked;
        }
        headers.forEach((key, values) -> {
            if (SENSITIVE_HEADERS.contains(key.toLowerCase())) {
                masked.set(key, MASK);
            } else {
                masked.put(key, values);
            }
        });
        return masked;
    }
}
