package com.twixor.base64convertor.common.service;

import com.twixor.base64convertor.common.model.DetectionResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class FileTypeDetectionService {

    private static final Tika tika = new Tika();

    // Map of MIME types to file extensions
    private static final Map<String, String> MIME_TO_EXTENSION = Map.ofEntries(
            Map.entry("application/pdf", ".pdf"),
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/png", ".png"),
            Map.entry("image/gif", ".gif"),
            Map.entry("image/webp", ".webp"),
            Map.entry("image/bmp", ".bmp"),
            Map.entry("image/tiff", ".tiff"),
            Map.entry("video/mp4", ".mp4"),
            Map.entry("video/mpeg", ".mpeg"),
            Map.entry("video/quicktime", ".mov"),
            Map.entry("audio/mpeg", ".mp3"),
            Map.entry("audio/wav", ".wav"),
            Map.entry("audio/aac", ".aac"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"),
            Map.entry("application/msword", ".doc"),
            Map.entry("application/vnd.ms-excel", ".xls"),
            Map.entry("application/vnd.ms-powerpoint", ".ppt"),
            Map.entry("application/zip", ".zip"),
            Map.entry("application/x-rar-compressed", ".rar"),
            Map.entry("application/x-7z-compressed", ".7z"),
            Map.entry("application/gzip", ".gz"),
            Map.entry("text/plain", ".txt"),
            Map.entry("text/csv", ".csv"),
            Map.entry("text/html", ".html"),
            Map.entry("text/xml", ".xml"),
            Map.entry("application/json", ".json"),
            Map.entry("application/xml", ".xml"),
            Map.entry("application/javascript", ".js")
    );

    /**
     * Detect MIME type from Base64 encoded content using Tika.
     *
     * @param base64Content the Base64 encoded content
     * @return DetectionResult containing MIME type and file extension
     */
    public DetectionResult detectFileType(String base64Content) {
        try {
            // Validate Base64 content is not null or empty
            if (base64Content == null || base64Content.isBlank()) {
                log.warn("Base64 content is null or empty");
                return DetectionResult.builder()
                        .success(false)
                        .error("Base64 content cannot be empty")
                        .mimeType("application/octet-stream")
                        .extension(".bin")
                        .build();
            }

            // Decode Base64 to bytes
            byte[] decodedBytes;
            try {
                decodedBytes = Base64.getDecoder().decode(base64Content);
            } catch (IllegalArgumentException e) {
                log.error("Invalid Base64 content: {}", e.getMessage());
                return DetectionResult.builder()
                        .success(false)
                        .error("Invalid Base64 content: " + e.getMessage())
                        .mimeType("application/octet-stream")
                        .extension(".bin")
                        .build();
            }

            return detectFileType(decodedBytes);

        } catch (Exception e) {
            log.error("Error detecting file type: {}", e.getMessage(), e);
            return DetectionResult.builder()
                    .success(false)
                    .error("Error detecting file type: " + e.getMessage())
                    .mimeType("application/octet-stream")
                    .extension(".bin")
                    .build();
        }
    }

    /**
     * Detect MIME type from already-decoded bytes using Tika. Callers that already hold the
     * decoded content (e.g. because they needed it for something else first) should use this
     * overload instead of {@link #detectFileType(String)} to avoid decoding the same Base64
     * string twice per request.
     *
     * @param decodedBytes the raw, already-decoded file content
     * @return DetectionResult containing MIME type and file extension
     */
    public DetectionResult detectFileType(byte[] decodedBytes) {
        try {
            // Validate decoded content is not empty
            if (decodedBytes == null || decodedBytes.length == 0) {
                log.warn("Decoded Base64 content is empty");
                return DetectionResult.builder()
                        .success(false)
                        .error("Decoded content is empty")
                        .mimeType("application/octet-stream")
                        .extension(".bin")
                        .build();
            }

            // Warn if file is very small - detection may be unreliable
            if (decodedBytes.length < 10) {
                log.warn("File content is very small ({}B), MIME type detection may be unreliable", decodedBytes.length);
            }

            // Detect MIME type using Tika
            String mimeType = tika.detect(decodedBytes);
            log.info("Detected MIME type: {} for {} bytes of content", mimeType, decodedBytes.length);

            // Get file extension from MIME type
            String extension = MIME_TO_EXTENSION.getOrDefault(mimeType, ".bin");

            return DetectionResult.builder()
                    .mimeType(mimeType)
                    .extension(extension)
                    .success(true)
                    .build();

        } catch (Exception e) {
            log.error("Error detecting file type: {}", e.getMessage(), e);
            return DetectionResult.builder()
                    .success(false)
                    .error("Error detecting file type: " + e.getMessage())
                    .mimeType("application/octet-stream")
                    .extension(".bin")
                    .build();
        }
    }

    /**
     * Detect file type and return only the extension.
     */
    public String detectExtension(String base64Content) {
        return detectFileType(base64Content).extension;
    }

    /**
     * Detect file type and return only the MIME type.
     */
    public String detectMimeType(String base64Content) {
        return detectFileType(base64Content).mimeType;
    }
}
