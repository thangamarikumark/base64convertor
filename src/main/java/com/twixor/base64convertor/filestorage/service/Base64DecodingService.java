package com.twixor.base64convertor.filestorage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.twixor.base64convertor.common.config.AppProperties;
import com.twixor.base64convertor.common.model.DetectionResult;
import com.twixor.base64convertor.common.service.FileTypeDetectionService;
import com.twixor.base64convertor.common.util.FileNameSanitizer;
import com.twixor.base64convertor.common.util.FileSizeFormatter;
import com.twixor.base64convertor.filestorage.dto.Base64SaveRequest;
import com.twixor.base64convertor.filestorage.model.DecodedFileResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class Base64DecodingService {

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final FileTypeDetectionService fileTypeDetectionService;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${server.port:8080}")
    private String serverPort;

    public Base64DecodingService(AppProperties appProperties, ObjectMapper objectMapper,
                                 FileTypeDetectionService fileTypeDetectionService) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.fileTypeDetectionService = fileTypeDetectionService;
    }

    /**
     * Decode Base64 content, save as binary file, and save metadata.
     * File type is auto-detected using Tika from the Base64 content.
     */
    public DecodedFileResult decodeAndSaveFile(Base64SaveRequest request) throws IOException {
        // Validate configuration
        if (appProperties == null || appProperties.getBase64() == null) {
            throw new IOException("Base64 configuration is not available");
        }

        String outputPath = appProperties.getBase64().getOutputPath();
        if (outputPath == null || outputPath.isBlank()) {
            throw new IOException("Output path is not configured. Please set app.base64.output-path");
        }

        Path dir = Paths.get(outputPath);
        Files.createDirectories(dir);

        // Decode Base64 to bytes
        byte[] decodedBytes = Base64.getDecoder().decode(request.getBase64Content());

        long maxBytes = appProperties.getMaxFileSizeMb() * 1024L * 1024L;
        if (decodedBytes.length > maxBytes) {
            throw new IllegalArgumentException(
                    "decoded content exceeds maximum allowed size of " + appProperties.getMaxFileSizeMb() + " MB");
        }

        // Auto-detect file type using Tika (reuses the bytes decoded above instead of
        // re-decoding the same Base64 string a second time)
        DetectionResult typeDetection = fileTypeDetectionService.detectFileType(decodedBytes);
        String detectedMimeType = typeDetection.mimeType;
        String detectedExtension = typeDetection.extension;

        log.info("Detected file type - MIME: {}, Extension: {}", detectedMimeType, detectedExtension);

        // Generate unique filename with detected extension
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String sanitizedFileName = FileNameSanitizer.sanitize(request.getFileName(), "file");

        // Validate sanitized filename is not empty
        if (sanitizedFileName.isBlank()) {
            log.warn("Original fileName resulted in empty string after sanitization: {}", request.getFileName());
            sanitizedFileName = "file";
        }

        // Remove existing extension if present
        if (sanitizedFileName.contains(".")) {
            sanitizedFileName = sanitizedFileName.substring(0, sanitizedFileName.lastIndexOf("."));
        }

        // Final validation - ensure we have content before extension
        if (sanitizedFileName.isBlank()) {
            sanitizedFileName = "file";
        }

        String fileName = timestamp + "_" + shortId + "_" + sanitizedFileName + detectedExtension;

        // Create file path
        Path filePath = dir.resolve(fileName);

        // Save decoded file
        Files.write(filePath, decodedBytes);
        log.info("Saved decoded file: {} (size: {} bytes)", fileName, decodedBytes.length);

        // Save metadata
        Map<String, Object> metadata = buildMetadata(request, fileName, decodedBytes.length, detectedMimeType);
        Path metadataPath = dir.resolve(fileName + ".meta.json");
        Files.writeString(metadataPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
        log.info("Saved metadata: {}", metadataPath.getFileName());

        // Build download link
        String downloadLink = buildDownloadLink(fileName);

        return DecodedFileResult.builder()
                .fileName(fileName)
                .originalFileName(request.getFileName())
                .fileSize(FileSizeFormatter.format(decodedBytes.length))
                .fileSizeBytes(decodedBytes.length)
                .mimeType(detectedMimeType)
                .downloadLink(downloadLink)
                .metadataFile(metadataPath.getFileName().toString())
                .savedAt(LocalDateTime.now().toString())
                .build();
    }

    private Map<String, Object> buildMetadata(Base64SaveRequest request, String savedFileName, long fileSize, String detectedMimeType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("originalFileName", request.getFileName());
        metadata.put("savedFileName", savedFileName);
        metadata.put("fileSize", fileSize);
        metadata.put("detectedMimeType", detectedMimeType);
        metadata.put("savedAt", LocalDateTime.now().toString());
        metadata.put("extraParameters", request.getExtraParams());
        return metadata;
    }

    private String buildDownloadLink(String fileName) {
        String baseUrl = resolveBaseUrl();
        return baseUrl + contextPath + "/api/files/convert/download-decoded/" + fileName;
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
