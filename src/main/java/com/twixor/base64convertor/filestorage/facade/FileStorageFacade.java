package com.twixor.base64convertor.filestorage.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.twixor.base64convertor.common.config.AppProperties;
import com.twixor.base64convertor.common.util.FileNameSanitizer;
import com.twixor.base64convertor.common.util.FileSizeFormatter;
import com.twixor.base64convertor.common.validation.PathTraversalGuard;
import com.twixor.base64convertor.filestorage.dto.Base64SaveRequest;
import com.twixor.base64convertor.filestorage.dto.Base64SaveResponse;
import com.twixor.base64convertor.filestorage.dto.DecodedFileInfo;
import com.twixor.base64convertor.filestorage.dto.FileInfo;
import com.twixor.base64convertor.filestorage.model.DecodedFileResult;
import com.twixor.base64convertor.filestorage.service.Base64DecodingService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Orchestration layer for the filestorage module (Phase A, A11a). Absorbs the filesystem
 * I/O, path-traversal guarding, and response-shaping logic that previously lived directly
 * inside {@code FileRetrievalController}'s 8 endpoint methods (5 of which had no service
 * layer at all before this refactor). Controllers built on top of this facade decide the
 * HTTP status code for each outcome (403/404/500) exactly as they did before; this facade
 * only decides *whether* an outcome is forbidden/not-found/ok, via well-known IOException
 * subtypes, so the controller's catch-block-to-status-code mapping is unchanged.
 */
@Component
@RequiredArgsConstructor
public class FileStorageFacade {

    private static final Logger logger = LogManager.getLogger(FileStorageFacade.class);
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AppProperties appProperties;
    private final Base64DecodingService base64DecodingService;
    private final PathTraversalGuard pathTraversalGuard;
    private final ObjectMapper objectMapper;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${server.port:8080}")
    private String serverPort;

    public DecodedFileResult decodeAndSave(Base64SaveRequest request) throws IOException {
        return base64DecodingService.decodeAndSaveFile(request);
    }

    /** @throws AccessDeniedException on traversal attempt or .meta.json access; NoSuchFileException if missing */
    public byte[] readDecodedFile(String fileName) throws IOException {
        String outputPath = appProperties.getBase64().getOutputPath();
        Path filePath = Paths.get(outputPath).resolve(fileName);

        if (!pathTraversalGuard.isWithin(filePath, Paths.get(outputPath))) {
            logger.warn("Attempt to access file outside output directory: {}", fileName);
            throw new AccessDeniedException(fileName);
        }
        if (!Files.exists(filePath)) {
            logger.warn("File not found: {}", fileName);
            throw new NoSuchFileException(fileName);
        }
        if (fileName.endsWith(".meta.json")) {
            logger.warn("Attempt to download metadata file: {}", fileName);
            throw new AccessDeniedException(fileName);
        }

        byte[] fileContent = Files.readAllBytes(filePath);
        logger.info("Downloaded decoded file: {}", fileName);
        return fileContent;
    }

    /** @throws AccessDeniedException on traversal attempt; NoSuchFileException if missing */
    public String readMetadata(String fileName) throws IOException {
        String outputPath = appProperties.getBase64().getOutputPath();
        Path metadataPath = Paths.get(outputPath).resolve(fileName + ".meta.json");

        if (!pathTraversalGuard.isWithin(metadataPath, Paths.get(outputPath))) {
            logger.warn("Attempt to access metadata outside output directory: {}", fileName);
            throw new AccessDeniedException(fileName);
        }
        if (!Files.exists(metadataPath)) {
            logger.warn("Metadata not found: {}", fileName);
            throw new NoSuchFileException(fileName);
        }

        String metadata = Files.readString(metadataPath);
        logger.info("Retrieved metadata for file: {}", fileName);
        return metadata;
    }

    /**
     * Lists decoded files (POST /save-decoded output) in the configured output directory.
     *
     * <p>A "decoded file" is identified the same way {@code Base64OutputWriter.cleanupOldDecodedFiles}
     * already identifies one for retention cleanup — any regular file that is neither a {@code .b64}
     * output nor a {@code .meta.json} sidecar — so this listing and that cleanup never disagree on
     * what counts as a decoded file.
     */
    public List<DecodedFileInfo> listDecodedFiles() throws IOException {
        String outputPath = appProperties.getBase64().getOutputPath();
        Path dir = Paths.get(outputPath);

        if (!Files.exists(dir)) {
            return List.of();
        }

        int retentionDays = appProperties.getDecodedFile().getRetentionDays();
        List<DecodedFileInfo> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toString().endsWith(".b64"))
                    .filter(p -> !p.toString().endsWith(".meta.json"))
                    .map(p -> toDecodedFileInfo(p, retentionDays))
                    .filter(info -> info != null)
                    .collect(Collectors.toList());
        }

        logger.info("Listed {} decoded files from {}", files.size(), outputPath);
        return files;
    }

    private DecodedFileInfo toDecodedFileInfo(Path primary, int retentionDays) {
        try {
            Instant createdInstant = Files.getLastModifiedTime(primary).toInstant();
            return DecodedFileInfo.builder()
                    .fileName(primary.getFileName().toString())
                    .size(Files.size(primary))
                    .createdDate(createdInstant.toString())
                    .mimeType(readDetectedMimeType(primary))
                    .expiryDate(createdInstant.plus(retentionDays, ChronoUnit.DAYS).toString())
                    .build();
        } catch (IOException e) {
            logger.warn("Error reading decoded file info for {}: {}", primary, e.getMessage());
            return null;
        }
    }

    /** Reads the Tika-detected MIME type recorded in the file's .meta.json sidecar at save time. */
    private String readDetectedMimeType(Path primary) {
        Path sidecar = primary.resolveSibling(primary.getFileName() + ".meta.json");
        if (!Files.exists(sidecar)) {
            return "application/octet-stream";
        }
        try {
            JsonNode metadata = objectMapper.readTree(sidecar.toFile());
            JsonNode mimeType = metadata.get("detectedMimeType");
            return mimeType != null ? mimeType.asText() : "application/octet-stream";
        } catch (IOException e) {
            logger.warn("Could not read metadata sidecar for {}: {}", primary, e.getMessage());
            return "application/octet-stream";
        }
    }

    public Base64SaveResponse saveRawBase64(Base64SaveRequest request) throws IOException {
        String outputPath = appProperties.getBase64().getOutputPath();
        Path dir = Paths.get(outputPath);
        Files.createDirectories(dir);

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String safeName = FileNameSanitizer.sanitize(request.getFileName(), "file");
        String outFileName = timestamp + "_" + shortId + "_" + safeName + ".b64";

        Path filePath = dir.resolve(outFileName);
        Files.writeString(filePath, request.getBase64Content(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        long fileSize = Files.size(filePath);
        String downloadLink = buildDownloadLink(outFileName);

        // Logging remediation, HIGH #2: never log the caller-supplied, schema-less extraParams map.
        logger.info("Saved Base64 file: {} size={} bytes", outFileName, fileSize);

        return Base64SaveResponse.builder()
                .success(true)
                .message("File saved successfully")
                .fileName(outFileName)
                .downloadLink(downloadLink)
                .fileSize(FileSizeFormatter.format(fileSize))
                .savedAt(LocalDateTime.now().toString())
                .build();
    }

    public List<FileInfo> listBase64Files() throws IOException {
        String outputPath = appProperties.getBase64().getOutputPath();
        Path dir = Paths.get(outputPath);

        if (!Files.exists(dir)) {
            return List.of();
        }

        List<FileInfo> files = Files.list(dir)
                .filter(p -> p.toString().endsWith(".b64"))
                .map(p -> {
                    try {
                        return FileInfo.builder()
                                .fileName(p.getFileName().toString())
                                .size(Files.size(p))
                                .lastModified(Files.getLastModifiedTime(p).toInstant().toString())
                                .build();
                    } catch (IOException e) {
                        logger.warn("Error reading file info for {}: {}", p, e.getMessage());
                        return null;
                    }
                })
                .filter(f -> f != null)
                .collect(Collectors.toList());

        logger.info("Listed {} Base64 files from {}", files.size(), outputPath);
        return files;
    }

    /** @throws AccessDeniedException on traversal attempt or non-.b64 file; NoSuchFileException if missing */
    public String readBase64File(String fileName) throws IOException {
        String outputPath = appProperties.getBase64().getOutputPath();
        Path filePath = Paths.get(outputPath).resolve(fileName);

        if (!pathTraversalGuard.isWithin(filePath, Paths.get(outputPath))) {
            logger.warn("Attempt to access file outside output directory: {}", fileName);
            throw new AccessDeniedException(fileName);
        }
        if (!Files.exists(filePath)) {
            logger.warn("File not found: {}", fileName);
            throw new NoSuchFileException(fileName);
        }
        if (!fileName.endsWith(".b64")) {
            logger.warn("Attempt to access non-b64 file: {}", fileName);
            throw new AccessDeniedException(fileName);
        }

        String content = Files.readString(filePath);
        logger.info("Read Base64 file: {}", fileName);
        return content;
    }

    /** @throws AccessDeniedException on traversal attempt or non-.b64 file */
    public boolean deleteBase64File(String fileName) throws IOException {
        String outputPath = appProperties.getBase64().getOutputPath();
        Path filePath = Paths.get(outputPath).resolve(fileName);

        if (!pathTraversalGuard.isWithin(filePath, Paths.get(outputPath))) {
            logger.warn("Attempt to delete file outside output directory: {}", fileName);
            throw new AccessDeniedException(fileName);
        }
        if (!fileName.endsWith(".b64")) {
            logger.warn("Attempt to delete non-b64 file: {}", fileName);
            throw new AccessDeniedException(fileName);
        }

        boolean deleted = Files.deleteIfExists(filePath);
        if (deleted) {
            logger.info("Deleted file: {}", fileName);
        } else {
            logger.warn("File not found for deletion: {}", fileName);
        }
        return deleted;
    }

    private String buildDownloadLink(String fileName) {
        String baseUrl = resolveBaseUrl();
        return baseUrl + contextPath + "/api/files/convert/download/" + fileName;
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
}
