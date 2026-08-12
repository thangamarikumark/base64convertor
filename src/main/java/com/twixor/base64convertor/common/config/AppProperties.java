package com.twixor.base64convertor.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized application configuration.
 * All values are configurable in application.properties under the "app" prefix.
 * Example: app.http.connect-timeout-seconds=30
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Http http = new Http();
    private Pdf pdf = new Pdf();
    private Convert convert = new Convert();
    private Url url = new Url();
    private Retry retry = new Retry();

    /**
     * Public base URL (scheme+host+port) used to build download links returned to callers,
     * e.g. {@code https://api.example.com}. When blank (the default), download-link builders
     * fall back to {@code http://localhost:<server.port>} for local/dev convenience — which is
     * NOT reachable by a real remote caller, so this must be set in every non-local deployment.
     */
    private String publicBaseUrl = "";

    /**
     * Maximum accepted file size, in megabytes, for endpoints that read an entire file into
     * memory in one shot: POST /api/files/convert, /convert/single, and /save-decoded. Mirrors
     * the same cap already enforced independently on /pdf/protect (pdf.protection.max-file-size-mb)
     * and the PDF dynamic-fetch streaming threshold (app.pdf.stream-threshold-bytes).
     */
    private long maxFileSizeMb = 20;

    @Getter
    @Setter
    public static class Http {
        /** Seconds to wait for a connection to be established. */
        private int connectTimeoutSeconds = 30;
        /** Seconds to wait for a response after the connection is made. */
        private int responseTimeoutSeconds = 60;
        /** Maximum total HTTP connections in the pool. */
        private int maxConnections = 200;
        /** Maximum connections per individual route/host. */
        private int maxConnectionsPerRoute = 20;
        /** When true, all SSL certificates are trusted (useful for internal/self-signed certs). */
        private boolean trustAllSsl = true;
    }

    @Getter
    @Setter
    public static class Pdf {
        /** Files larger than this byte count return FILE_TOO_LARGE instead of Base64. */
        private long streamThresholdBytes = 5_000_000L;
    }

    @Getter
    @Setter
    public static class Convert {
        /** Maximum number of files accepted in a single /convert batch request. */
        private int maxBatchSize = 50;
    }

    @Getter
    @Setter
    public static class Url {
        /**
         * When true, only URLs whose host appears in allowed-hosts are accepted.
         * Scheme validation (http/https only) is always enforced regardless of this flag.
         */
        private boolean allowlistEnabled = false;
        /**
         * Comma-separated list of permitted hostnames.
         * Example: app.url.allowed-hosts=api.partner.com,files.internal.com
         * Ignored when allowlist-enabled=false.
         */
        private List<String> allowedHosts = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Retry {
        /** How many total attempts (1 = no retry). */
        private int maxAttempts = 3;
        /** Milliseconds to wait before the first retry. */
        private long initialDelayMs = 1000;
        /** Each subsequent retry delay is multiplied by this factor. */
        private double multiplier = 2.0;
    }

    @Getter
    @Setter
    public static class Base64Output {
        /** When true, each conversion writes its Base64 string to a .b64 file on disk. */
        private boolean enabled = true;
        /** Directory where .b64 output files are written. */
        private String outputPath = "base64-output";
        /** .b64 files older than this many days are deleted by the scheduled cleanup. */
        private int retentionDays = 7;
    }

    @Getter
    @Setter
    public static class DecodedFile {
        /**
         * Decoded files written by POST /save-decoded (and their .meta.json sidecars) older
         * than this many days are deleted by the scheduled/startup cleanup. Independent of
         * app.base64.retention-days since decoded files live in the same output directory as
         * .b64 files but are a structurally different, previously-uncleaned category
         * (see docs/Production_Readiness_Review.md, P0-3).
         */
        private int retentionDays = 7;
    }

    private Base64Output base64 = new Base64Output();
    private DecodedFile decodedFile = new DecodedFile();
}
