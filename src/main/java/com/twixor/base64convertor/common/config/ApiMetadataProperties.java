package com.twixor.base64convertor.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the {@code X-API-Version} header value, read from
 * {@code app.api.version}. Bound via {@code @ConfigurationProperties} (not {@code @Value}) so it
 * stays consistent with the rest of this codebase's config-binding pattern (e.g.
 * {@code pdf.protection.*}) and so the version can be changed in exactly one place per release.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.api")
public class ApiMetadataProperties {

    private String version = "1.0.0";
}
