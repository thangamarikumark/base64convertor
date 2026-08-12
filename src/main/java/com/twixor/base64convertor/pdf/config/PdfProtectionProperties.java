package com.twixor.base64convertor.pdf.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for POST /api/files/pdf/protect, bound to the {@code pdf.protection.*} prefix
 * (module-local config, following the same pattern as {@code fileconversion.config.FileCacheProperties}
 * rather than being folded into the shared {@code app.*}-prefixed AppProperties).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "pdf.protection")
public class PdfProtectionProperties {

    /** Kill-switch for the endpoint; when false, requests fail fast with HTTP 503. */
    private boolean enabled = true;

    /** Maximum accepted decoded PDF size, in megabytes. */
    private int maxFileSizeMb = 20;

    /** Filename used in the Content-Disposition header of the returned attachment. */
    private String defaultFilename = "protected_document.pdf";
}
