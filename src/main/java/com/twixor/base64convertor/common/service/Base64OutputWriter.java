package com.twixor.base64convertor.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.twixor.base64convertor.common.config.AppProperties;
import com.twixor.base64convertor.common.model.BinaryWriteResult;
import com.twixor.base64convertor.common.util.FileNameSanitizer;
import com.twixor.base64convertor.common.util.FileSizeFormatter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Writes generated Base64 strings to individual .b64 files on disk so they
 * can be easily opened and copied — avoiding unreadable log output.
 *
 * Configuration (application.properties):
 *   app.base64.output-enabled  — toggle on/off (default true)
 *   app.base64.output-path     — directory for .b64 files
 *   app.base64.retention-days  — files older than this are deleted by cleanup
 */
@Service
public class Base64OutputWriter {

    private static final Logger log = LogManager.getLogger(Base64OutputWriter.class);
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AppProperties appProperties;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${server.port:8080}")
    private String serverPort;

    private Counter filesWrittenCounter;
    private Counter filesDeletedCounter;
    private Counter writeErrorCounter;
    private Counter decodedFilesDeletedCounter;
    private Counter decodedFilesDeleteErrorCounter;

    public Base64OutputWriter(AppProperties appProperties, MeterRegistry meterRegistry, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initMetrics() {
        filesWrittenCounter = Counter.builder("base64.output.files.written")
                .description("Number of .b64 output files successfully written to disk")
                .register(meterRegistry);
        filesDeletedCounter = Counter.builder("base64.output.files.deleted")
                .description("Number of expired .b64 output files deleted by scheduled cleanup")
                .register(meterRegistry);
        writeErrorCounter = Counter.builder("base64.output.files.errors")
                .description("Number of failures when writing .b64 output files")
                .register(meterRegistry);
        decodedFilesDeletedCounter = Counter.builder("base64.output.decoded-files.deleted")
                .description("Number of expired decoded files (and .meta.json sidecars) from " +
                        "POST /save-decoded deleted by scheduled/startup cleanup")
                .register(meterRegistry);
        decodedFilesDeleteErrorCounter = Counter.builder("base64.output.decoded-files.delete-errors")
                .description("Number of failures when deleting expired decoded files or their sidecars")
                .register(meterRegistry);
    }

    /**
     * Runs cleanup once at startup, in addition to the hourly {@code @Scheduled} cleanup in
     * {@code FileConversionService} — so a service that was down past the retention window
     * doesn't wait up to an hour after restart before reclaiming already-expired disk space.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        log.info("Running startup cleanup of expired Base64/decoded output files...");
        cleanupOldFiles();
        cleanupOldDecodedFiles();
    }

    /**
     * Writes {@code base64} to a uniquely named .b64 file.
     *
     * File name format: {@code yyyyMMdd-HHmmss_<8-char-uuid>_<safeFileName>.b64}
     *
     * @param base64   the Base64 string to persist
     * @param fileName original file name (used only in the output file name for readability)
     * @return absolute path of the written file, or {@code null} if output is disabled or writing fails
     */
    public Path write(String base64, String fileName) {
        AppProperties.Base64Output cfg = appProperties.getBase64();
        if (!cfg.isEnabled()) {
            return null;
        }

        try {
            Path dir = Paths.get(cfg.getOutputPath());
            Files.createDirectories(dir);

            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            String safeName = FileNameSanitizer.sanitize(fileName, "file");
            String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String outName = timestamp + "_" + shortId + "_" + safeName + ".b64";

            Path outFile = dir.resolve(outName);
            Files.writeString(outFile, base64, StandardOpenOption.CREATE_NEW);
            log.info("Base64 written to file: {}", outFile.toAbsolutePath());
            filesWrittenCounter.increment();
            return outFile;

        } catch (IOException e) {
            log.warn("Could not write Base64 output file: {}", e.getMessage());
            writeErrorCounter.increment();
            return null;
        }
    }

    /**
     * Deletes .b64 files in the configured output directory that are older than
     * {@code app.base64.retention-days} days. Called by the scheduled cleanup in
     * {@code FileConversionService}.
     */
    public void cleanupOldFiles() {
        AppProperties.Base64Output cfg = appProperties.getBase64();
        if (!cfg.isEnabled()) {
            return;
        }

        Path dir = Paths.get(cfg.getOutputPath());
        if (!Files.exists(dir)) {
            return;
        }

        Instant cutoff = Instant.now().minus(cfg.getRetentionDays(), ChronoUnit.DAYS);

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".b64"))
                 .filter(p -> {
                     try {
                         FileTime lastModified = Files.getLastModifiedTime(p);
                         return lastModified.toInstant().isBefore(cutoff);
                     } catch (IOException ex) {
                         return false;
                     }
                 })
                 .forEach(p -> {
                     try {
                         Files.deleteIfExists(p);
                         log.info("Deleted old Base64 output file: {}", p.getFileName());
                         filesDeletedCounter.increment();
                     } catch (IOException ex) {
                         log.warn("Could not delete Base64 output file {}: {}", p, ex.getMessage());
                     }
                 });
        } catch (Exception e) {
            log.error("Error cleaning up Base64 output files", e);
        }
    }

    /**
     * Deletes expired decoded files written by {@code POST /save-decoded}
     * ({@code Base64DecodingService.decodeAndSaveFile}) along with their {@code .meta.json}
     * sidecars, from the same {@code app.base64.output-path} directory used for {@code .b64}
     * output. Retention is governed independently by {@code app.decoded-file.retention-days}.
     *
     * <p>Decoded files never carry a {@code .b64} extension (they use the Tika-detected
     * extension, e.g. {@code .pdf}/{@code .jpg}), so {@link #cleanupOldFiles()}'s {@code .b64}
     * filter never matches them — this is the dedicated cleanup path for that category, closing
     * the unbounded-disk-growth gap documented in
     * {@code docs/Production_Readiness_Review.md} (P0-3).
     *
     * <p>A "primary" decoded file and its {@code <fileName>.meta.json} sidecar are deleted
     * together, keyed off the primary file's age, so the two never drift out of sync. A second
     * pass separately reclaims any orphaned {@code .meta.json} file whose primary file is
     * already gone (e.g. from a prior partial failure), so a sidecar can never accumulate
     * forever even if its primary was removed by some other means.
     */
    public void cleanupOldDecodedFiles() {
        AppProperties.Base64Output outputCfg = appProperties.getBase64();
        if (!outputCfg.isEnabled()) {
            return;
        }

        Path dir = Paths.get(outputCfg.getOutputPath());
        if (!Files.exists(dir)) {
            return;
        }

        int retentionDays = appProperties.getDecodedFile().getRetentionDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deletedCount = 0;
        long deletedBytes = 0L;

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> primaryFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toString().endsWith(".b64"))
                    .filter(p -> !p.toString().endsWith(".meta.json"))
                    .collect(Collectors.toList());

            for (Path primary : primaryFiles) {
                if (!isExpired(primary, cutoff)) {
                    continue;
                }
                long size = safeSize(primary);
                Path metadataSidecar = dir.resolve(primary.getFileName() + ".meta.json");
                boolean primaryDeleted = safeDelete(primary, "decoded file");
                boolean sidecarDeleted = !Files.exists(metadataSidecar)
                        || safeDelete(metadataSidecar, "decoded file metadata sidecar");

                if (primaryDeleted) {
                    deletedCount++;
                    deletedBytes += size;
                    decodedFilesDeletedCounter.increment();
                    log.info("Deleted expired decoded file (retention={}d): {} ({} bytes, sidecar deleted={})",
                            retentionDays, primary.getFileName(), size, sidecarDeleted);
                }
            }
        } catch (Exception e) {
            log.error("Error cleaning up expired decoded files", e);
        }

        // Second pass: reclaim orphaned .meta.json sidecars whose primary file is already gone.
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> orphanCandidates = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".meta.json"))
                    .collect(Collectors.toList());

            for (Path metaFile : orphanCandidates) {
                String metaName = metaFile.getFileName().toString();
                Path primary = dir.resolve(metaName.substring(0, metaName.length() - ".meta.json".length()));
                if (Files.exists(primary)) {
                    continue; // not an orphan, handled (or will be handled) above
                }
                if (!isExpired(metaFile, cutoff)) {
                    continue;
                }
                if (safeDelete(metaFile, "orphaned decoded file metadata sidecar")) {
                    deletedCount++;
                    decodedFilesDeletedCounter.increment();
                    log.info("Deleted orphaned decoded file metadata sidecar (retention={}d): {}",
                            retentionDays, metaFile.getFileName());
                }
            }
        } catch (Exception e) {
            log.error("Error cleaning up orphaned decoded file metadata sidecars", e);
        }

        if (deletedCount > 0) {
            log.info("Decoded-file cleanup summary: deleted {} file(s), reclaimed {} bytes, retention={}d, directory={}",
                    deletedCount, deletedBytes, retentionDays, dir);
        }
    }

    /**
     * Uses the configured public base URL (app.public-base-url) when set, since a real caller
     * cannot resolve "localhost" against anything but the machine the server itself runs on.
     * Falls back to http://localhost:<port> only for local/dev convenience when unconfigured.
     */
    private String resolveBaseUrl() {
        String configured = appProperties.getPublicBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
        return "http://localhost:" + serverPort;
    }

    private boolean isExpired(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException ex) {
            return false;
        }
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0L;
        }
    }

    private boolean safeDelete(Path path, String description) {
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException ex) {
            log.warn("Could not delete {} {}: {}", description, path, ex.getMessage());
            decodedFilesDeleteErrorCounter.increment();
            return false;
        }
    }

    /**
     * Writes raw binary {@code content} to a uniquely named file, plus a companion
     * {@code .meta.json} sidecar, and returns a result with a download link built from
     * {@code /api/files/convert/download-decoded/<fileName>} — the same convention used by
     * {@code Base64DecodingService}, so the existing download/metadata endpoints in
     * {@code FileRetrievalController} serve these files without any changes.
     *
     * @param content   raw bytes to write
     * @param fileName  base name (without extension) used to build the output file name
     * @param extension file extension including the leading dot, e.g. ".pdf"
     * @param metadata  arbitrary metadata written to the .meta.json sidecar
     * @return a {@link BinaryWriteResult}, or {@code null} if writing fails
     */
    public BinaryWriteResult writeBinaryWithMetadata(byte[] content, String fileName, String extension,
                                                       Map<String, Object> metadata) {
        try {
            Path dir = Paths.get(appProperties.getBase64().getOutputPath());
            Files.createDirectories(dir);

            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            String safeName = FileNameSanitizer.sanitize(fileName, "file");
            String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String outName = timestamp + "_" + shortId + "_" + safeName + extension;

            Path outFile = dir.resolve(outName);
            Files.write(outFile, content, StandardOpenOption.CREATE_NEW);

            Path metadataPath = dir.resolve(outName + ".meta.json");
            Files.writeString(metadataPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));

            log.info("Wrote binary output file: {} ({} bytes)", outFile.toAbsolutePath(), content.length);
            filesWrittenCounter.increment();

            String downloadLink = resolveBaseUrl() + contextPath + "/api/files/convert/download-decoded/" + outName;

            return BinaryWriteResult.builder()
                    .fileName(outName)
                    .metadataFile(metadataPath.getFileName().toString())
                    .downloadLink(downloadLink)
                    .fileSize(FileSizeFormatter.format(content.length))
                    .fileSizeBytes(content.length)
                    .savedAt(LocalDateTime.now().toString())
                    .build();

        } catch (IOException e) {
            log.warn("Could not write binary output file: {}", e.getMessage());
            writeErrorCounter.increment();
            return null;
        }
    }
}
