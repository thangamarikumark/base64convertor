package com.twixor.base64convertor.fileconversion.service;

import com.twixor.base64convertor.common.config.AppProperties;
import com.twixor.base64convertor.fileconversion.config.FileCacheProperties;
import com.twixor.base64convertor.fileconversion.dto.FileConvertRequest;
import com.twixor.base64convertor.fileconversion.dto.FileConvertResponse;
import com.twixor.base64convertor.common.service.Base64OutputWriter;
import com.twixor.base64convertor.common.util.LogSanitizer;
import com.twixor.base64convertor.common.validation.UrlAllowlistValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class FileConversionService {

    // MIME type → file extension mapping. Extend this map to support new types.
    private static final Map<String, String> MIME_EXTENSION_MAP = Map.of(
            "application/pdf",     ".pdf",
            "image/jpeg",          ".jpg",
            "image/png",           ".png",
            "image/gif",           ".gif",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",       ".xlsx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx",
            "application/msword",  ".doc",
            "application/zip",     ".zip",
            "text/plain",          ".txt"
    );

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final FileCacheProperties cacheProps;
    private final AppProperties appProperties;
    private final UrlAllowlistValidator urlAllowlistValidator;
    private final Base64OutputWriter base64OutputWriter;
    private final MeterRegistry meterRegistry;

    private final Map<String, FileConvertResponse> asyncResults = new ConcurrentHashMap<>();
    private final Map<String, Instant> asyncResultTimes = new ConcurrentHashMap<>();

    // Metrics
    private Counter successCounter;
    private Counter failureCounter;
    private Timer conversionTimer;

    // Trust-all SSL support for HTTPS downloads (mirrors UnsafeRestTemplate's behavior,
    // gated by the same app.http.trust-all-ssl flag) so self-signed/internal HTTPS
    // sources download the same way http sources do.
    private SSLSocketFactory trustAllSslSocketFactory;
    private static final HostnameVerifier TRUST_ALL_HOSTNAME_VERIFIER = (hostname, session) -> true;

    public FileConversionService(FileCacheProperties cacheProps,
                                 AppProperties appProperties,
                                 UrlAllowlistValidator urlAllowlistValidator,
                                 Base64OutputWriter base64OutputWriter,
                                 MeterRegistry meterRegistry) {
        this.cacheProps = cacheProps;
        this.appProperties = appProperties;
        this.urlAllowlistValidator = urlAllowlistValidator;
        this.base64OutputWriter = base64OutputWriter;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(cacheProps.getPath()));
            log.info("Initialized cache directory: {}", cacheProps.getPath());
        } catch (IOException e) {
            // Fail fast: a cache directory that can't be created means every conversion
            // request would fail later anyway, so surface it as a startup error instead.
            throw new IllegalStateException(
                    "Failed to initialize file cache directory [" + cacheProps.getPath() + "]. " +
                    "Check that file.cache.path (env: FILE_CACHE_PATH) points to a writable location.", e);
        }
        successCounter = Counter.builder("base64.conversions")
                .tag("status", "success")
                .description("Number of successful file-to-Base64 conversions")
                .register(meterRegistry);
        failureCounter = Counter.builder("base64.conversions")
                .tag("status", "failure")
                .description("Number of failed file-to-Base64 conversions")
                .register(meterRegistry);
        conversionTimer = Timer.builder("base64.conversion.duration")
                .description("Time taken to download and encode a file to Base64")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        if (appProperties.getHttp().isTrustAllSsl()) {
            trustAllSslSocketFactory = buildTrustAllSslSocketFactory();
        }
    }

    private SSLSocketFactory buildTrustAllSslSocketFactory() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            log.warn("Failed to initialize trust-all SSL socket factory for HTTPS downloads; " +
                    "HTTPS downloads will fall back to default JVM trust store validation.", e);
            return null;
        }
    }

    /** Synchronous file processing. */
    public FileConvertResponse processFile(FileConvertRequest request) {
        return handleFileProcessing(request);
    }

    /**
     * Submits a file for asynchronous processing and returns the processingId immediately;
     * result can be polled via {@link #getAsyncResult(String)}.
     *
     * <p>The id is generated and the placeholder result registered synchronously, on the
     * caller's thread, <em>before</em> dispatching the actual work to {@link #processFileAsync}
     * on the fileExecutor pool — a caller invoking an {@code @Async}-proxied method directly
     * cannot read state computed inside that method without blocking on its returned future,
     * so the id has to exist before the async call is made, not inside it.
     */
    public String submitAsync(FileConvertRequest request) {
        String processingId = UUID.randomUUID().toString();

        FileConvertResponse placeholder = FileConvertResponse.builder()
                .processingId(processingId)
                .fileName(request.getFileName())
                .mimeType(request.getMimeType())
                .type(request.getType())
                .caption(request.getCaption())
                .success(false)
                .message("Processing")
                .build();

        asyncResults.put(processingId, placeholder);
        asyncResultTimes.put(processingId, Instant.now());

        processFileAsync(processingId, request);
        return processingId;
    }

    /** Runs on the configured fileExecutor thread pool; updates the result registered by {@link #submitAsync}. */
    @Async("fileExecutor")
    public void processFileAsync(String processingId, FileConvertRequest request) {
        try {
            FileConvertResponse response = handleFileProcessing(request);
            response.setProcessingId(processingId);
            asyncResults.put(processingId, response);
        } catch (Exception e) {
            FileConvertResponse placeholder = asyncResults.get(processingId);
            if (placeholder != null) {
                placeholder.setMessage("Failed: " + e.getMessage());
                asyncResults.put(processingId, placeholder);
            }
        }
    }

    public FileConvertResponse getAsyncResult(String processingId) {
        return asyncResults.get(processingId);
    }

    // ─── Core Processing ──────────────────────────────────────────────────────

    private FileConvertResponse handleFileProcessing(FileConvertRequest request) {
        Path tempFile = null;
        Timer.Sample timerSample = Timer.start(meterRegistry);

        try {
            if (request.getUrl() == null || request.getUrl().isEmpty())
                return errorResponse(request, "Missing URL");
            if (request.getMimeType() == null || request.getMimeType().isEmpty())
                return errorResponse(request, "Missing mimeType");
            if (request.getType() == null || request.getType().isEmpty())
                return errorResponse(request, "Missing type");

            // Validate URL scheme and optional host allowlist
            urlAllowlistValidator.validate(request.getUrl());

            String fileName = UUID.randomUUID() + getFileExtension(request.getMimeType());
            tempFile = Paths.get(cacheProps.getPath(), fileName);

            downloadWithRetry(new URL(request.getUrl()), tempFile);
            log.info("Downloaded: {} -> {}", LogSanitizer.sanitizeUrl(request.getUrl()), tempFile);

            long maxBytes = appProperties.getMaxFileSizeMb() * 1024L * 1024L;
            long downloadedSize = Files.size(tempFile);
            if (downloadedSize > maxBytes) {
                Files.deleteIfExists(tempFile);
                String msg = "File exceeds maximum allowed size of " + appProperties.getMaxFileSizeMb() + " MB";
                log.warn("{} ({} bytes, url={})", msg, downloadedSize, LogSanitizer.sanitizeUrl(request.getUrl()));
                logAudit(request, "FAILURE", msg);
                failureCounter.increment();
                timerSample.stop(conversionTimer);
                return errorResponse(request, msg);
            }

            byte[] fileBytes = Files.readAllBytes(tempFile);
            String base64Encoded = Base64.getEncoder().encodeToString(fileBytes);

            String displayName = Optional.ofNullable(request.getFileName()).orElse(fileName);
            Path b64File = base64OutputWriter.write(base64Encoded, displayName);
            log.info("Base64 output for '{}': {}", displayName,
                    b64File != null ? b64File.toAbsolutePath() : "(output disabled)");

            logAudit(request, "SUCCESS", "File converted successfully");
            Files.deleteIfExists(tempFile);
            successCounter.increment();
            timerSample.stop(conversionTimer);

            return FileConvertResponse.builder()
                    .fileName(Optional.ofNullable(request.getFileName()).orElse(tempFile.getFileName().toString()))
                    .mimeType(request.getMimeType())
                    .type(request.getType())
                    .caption(request.getCaption())
                    .base64Data(base64Encoded)
                    .success(true)
                    .message("File converted successfully")
                    .build();

        } catch (Exception e) {
            log.error("Error processing file: {}", LogSanitizer.sanitizeUrl(request.getUrl()), e);
            logAudit(request, "FAILURE", e.getMessage());
            safeDelete(tempFile);
            failureCounter.increment();
            timerSample.stop(conversionTimer);
            return errorResponse(request, e.getMessage());
        }
    }

    /**
     * Downloads a URL to a local path, retrying on IO failure.
     * Timeouts and retry parameters are driven by AppProperties (app.http.* and app.retry.*).
     */
    private void downloadWithRetry(URL url, Path target) throws IOException {
        AppProperties.Retry retryConfig = appProperties.getRetry();
        AppProperties.Http httpConfig = appProperties.getHttp();

        int maxAttempts = retryConfig.getMaxAttempts();
        long delay = retryConfig.getInitialDelayMs();
        IOException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                URLConnection connection = url.openConnection();
                if (connection instanceof HttpsURLConnection httpsConnection && trustAllSslSocketFactory != null) {
                    httpsConnection.setSSLSocketFactory(trustAllSslSocketFactory);
                    httpsConnection.setHostnameVerifier(TRUST_ALL_HOSTNAME_VERIFIER);
                }
                connection.setConnectTimeout(httpConfig.getConnectTimeoutSeconds() * 1000);
                connection.setReadTimeout(httpConfig.getResponseTimeoutSeconds() * 1000);
                try (InputStream in = connection.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return; // success
            } catch (IOException e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("Download attempt {}/{} failed for {}: {}. Retrying in {}ms...",
                            attempt, maxAttempts, url, e.getMessage(), delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry", ie);
                    }
                    delay = (long) (delay * retryConfig.getMultiplier());
                }
            }
        }
        throw new IOException("All " + maxAttempts + " download attempts failed for " + url, lastException);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void safeDelete(Path file) {
        try {
            if (file != null) Files.deleteIfExists(file);
        } catch (IOException ignored) {}
    }

    private FileConvertResponse errorResponse(FileConvertRequest request, String message) {
        return FileConvertResponse.builder()
                .fileName(request.getFileName())
                .mimeType(request.getMimeType())
                .type(request.getType())
                .caption(request.getCaption())
                .success(false)
                .message(message)
                .build();
    }

    private String getFileExtension(String mimeType) {
        if (mimeType == null) return ".tmp";
        return MIME_EXTENSION_MAP.getOrDefault(mimeType.toLowerCase(), ".bin");
    }

    // ─── Audit Logging ───────────────────────────────────────────────────────

    private void logAudit(FileConvertRequest request, String status, String message) {
        try {
            Path logDir = Paths.get(cacheProps.getPath(), "logs");
            Files.createDirectories(logDir);

            String logFileName = cacheProps.getAudit().isRotationEnabled()
                    ? "conversion_audit_" + LocalDate.now().format(DATE_FMT) + ".log"
                    : "conversion_audit.log";

            Path logFile = logDir.resolve(logFileName);

            String logLine = String.format(
                    "%s | %s | %s | %s | %s | %s%n",
                    Instant.now(), status,
                    Optional.ofNullable(request.getFileName()).orElse("N/A"),
                    request.getMimeType(), LogSanitizer.sanitizeUrl(request.getUrl()), message
            );

            Files.write(logFile, logLine.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        } catch (IOException e) {
            log.warn("Failed to write audit log: {}", e.getMessage());
        }
    }

    // ─── Scheduled Cleanup ───────────────────────────────────────────────────

    @Scheduled(cron = "0 0 * * * *")
    public void cleanupOldFilesAndLogs() {
        cleanupTempFiles();
        cleanupOldLogs();
        cleanupAsyncResults();
        base64OutputWriter.cleanupOldFiles();
        base64OutputWriter.cleanupOldDecodedFiles();
    }

    private void cleanupAsyncResults() {
        Instant cutoff = Instant.now().minus(cacheProps.getRetentionHours(), ChronoUnit.HOURS);
        asyncResultTimes.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(cutoff)) {
                asyncResults.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    private void cleanupTempFiles() {
        try {
            File dir = new File(cacheProps.getPath());
            if (!dir.exists()) return;

            long retentionMillis = TimeUnit.HOURS.toMillis(cacheProps.getRetentionHours());
            long now = System.currentTimeMillis();

            for (File file : Objects.requireNonNull(dir.listFiles())) {
                if (file.isFile() && now - file.lastModified() > retentionMillis) {
                    if (file.delete()) log.info("Deleted old cache file: {}", file.getName());
                }
            }
        } catch (Exception e) {
            log.error("Error cleaning up temp folder", e);
        }
    }

    private void cleanupOldLogs() {
        try {
            Path logDir = Paths.get(cacheProps.getPath(), "logs");
            if (!Files.exists(logDir)) return;

            File[] logFiles = logDir.toFile().listFiles((dir, name) -> name.endsWith(".log"));
            if (logFiles == null || logFiles.length == 0) return;

            if (cacheProps.getAudit().isArchiveEnabled()) {
                for (File logFile : logFiles) {
                    LocalDate logDate = extractDateFromLogFileName(logFile.getName());
                    if (logDate != null && logDate.isBefore(LocalDate.now())) {
                        Path zipFile = logDir.resolve(logFile.getName().replace(".log", ".zip"));
                        zipFile(logFile.toPath(), zipFile);
                        Files.deleteIfExists(logFile.toPath());
                        log.info("Archived log: {}", logFile.getName());
                    }
                }
            }

            int retentionDays = cacheProps.getAudit().getArchiveRetentionDays();
            for (File file : Objects.requireNonNull(logDir.toFile().listFiles())) {
                if (file.getName().endsWith(".zip")) {
                    long ageDays = Duration.between(Instant.ofEpochMilli(file.lastModified()), Instant.now()).toDays();
                    if (ageDays > retentionDays && file.delete()) log.info("Deleted old archive: {}", file.getName());
                }
            }

        } catch (Exception e) {
            log.error("Error cleaning up logs", e);
        }
    }

    private void zipFile(Path sourceFile, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()));
             FileInputStream fis = new FileInputStream(sourceFile.toFile())) {
            zos.putNextEntry(new ZipEntry(sourceFile.getFileName().toString()));
            fis.transferTo(zos);
            zos.closeEntry();
        }
    }

    private LocalDate extractDateFromLogFileName(String fileName) {
        try {
            if (fileName.startsWith("conversion_audit_") && fileName.endsWith(".log")) {
                String dateStr = fileName.substring("conversion_audit_".length(), fileName.length() - 4);
                return LocalDate.parse(dateStr, DATE_FMT);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
