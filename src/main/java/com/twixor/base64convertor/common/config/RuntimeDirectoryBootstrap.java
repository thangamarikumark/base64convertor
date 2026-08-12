package com.twixor.base64convertor.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Creates every directory the application writes to, before Spring Boot (and therefore
 * Log4j2's log4j2-spring.xml appenders) start initializing.
 *
 * <p>This runs from {@code main()} rather than as a Spring bean/{@code @PostConstruct}
 * because Spring Boot's logging system initializes before the ApplicationContext exists —
 * by the time any Spring bean could run, Log4j2 has already tried (and, on an unwritable
 * path, silently failed) to open its RollingFile appenders. Running here guarantees the
 * log directory exists first.
 *
 * <p>Each path is controlled by a single environment variable with a sensible Linux
 * default, so the same jar/image runs unmodified on any host:
 * <ul>
 *   <li>{@code FILE_CACHE_PATH}   (default {@code /var/lib/base64convertor/file-cache})</li>
 *   <li>{@code BASE64_OUTPUT_PATH} (default {@code /var/lib/base64convertor/base64-output})</li>
 *   <li>{@code LOG_DIR}           (default {@code /var/log/base64convertor})</li>
 * </ul>
 * These defaults must stay in sync with {@code application.properties} and
 * {@code log4j2-spring.xml}, which read the same environment variables independently.
 *
 * <p>Failure is fatal and loud by design: a directory that can't be created or written to
 * means the app cannot function, so it must not start in a half-working state with silent
 * logging/caching failures discovered only later in production.
 */
public final class RuntimeDirectoryBootstrap {

    private RuntimeDirectoryBootstrap() {
    }

    public static void initializeDirectories() {
        createAndVerifyWritable("FILE_CACHE_PATH", "/var/lib/base64convertor/file-cache");
        createAndVerifyWritable("BASE64_OUTPUT_PATH", "/var/lib/base64convertor/base64-output");
        createAndVerifyWritable("LOG_DIR", "/var/log/base64convertor");
    }

    private static void createAndVerifyWritable(String envVar, String defaultPath) {
        String configured = System.getenv(envVar);
        String resolved = (configured != null && !configured.isBlank()) ? configured : defaultPath;
        Path dir = Paths.get(resolved);

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException(startupFailureMessage(envVar, dir, e.getMessage()), e);
        }

        if (!Files.isWritable(dir)) {
            throw new IllegalStateException(startupFailureMessage(envVar, dir,
                    "directory exists but is not writable by the current process user"));
        }
    }

    private static String startupFailureMessage(String envVar, Path dir, String cause) {
        return "STARTUP FAILED: required directory [" + dir.toAbsolutePath() + "] "
                + "(configured via environment variable " + envVar + ") could not be created or "
                + "is not writable. Cause: " + cause + ". "
                + "Fix by either granting write permission to this path, or setting " + envVar
                + " to a writable location before starting the application.";
    }
}
