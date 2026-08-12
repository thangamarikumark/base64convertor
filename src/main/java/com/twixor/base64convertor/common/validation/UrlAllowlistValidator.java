package com.twixor.base64convertor.common.validation;

import com.twixor.base64convertor.common.config.AppProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Validates outbound URLs before any HTTP fetch is made.
 *
 * Always enforced:
 *   - Only http:// and https:// schemes are permitted (prevents file://, ftp://, etc.)
 *
 * Enforced when app.url.allowlist-enabled=true:
 *   - The URL host must appear in app.url.allowed-hosts
 */
@Component
public class UrlAllowlistValidator {

    private final AppProperties appProperties;

    public UrlAllowlistValidator(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * Validates the URL. Throws {@link IllegalArgumentException} if validation fails.
     *
     * @param url the URL to validate
     */
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL must not be blank");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Malformed URL: " + url);
        }

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "URL scheme '" + scheme + "' is not allowed. Only http and https are permitted.");
        }

        AppProperties.Url urlConfig = appProperties.getUrl();
        if (!urlConfig.isAllowlistEnabled()) return;

        List<String> allowedHosts = urlConfig.getAllowedHosts();
        if (allowedHosts.isEmpty()) return; // empty list = allow all hosts

        String host = uri.getHost();
        if (host == null || !allowedHosts.contains(host.toLowerCase())) {
            throw new IllegalArgumentException(
                    "URL host '" + host + "' is not in the allowed hosts list. " +
                    "Configure app.url.allowed-hosts to permit it.");
        }
    }
}
