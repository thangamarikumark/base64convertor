package com.twixor.base64convertor.filestorage.controller;

import com.twixor.base64convertor.filestorage.dto.Base64SaveRequest;
import com.twixor.base64convertor.filestorage.dto.DecodedFileInfo;
import com.twixor.base64convertor.filestorage.dto.DecodedFileSaveResponse;
import com.twixor.base64convertor.filestorage.facade.FileStorageFacade;
import com.twixor.base64convertor.filestorage.model.DecodedFileResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.List;

/**
 * Decode-and-save workflow endpoints, split out of the former FileRetrievalController
 * (Phase A, A1). URLs, request/response DTOs, status codes unchanged.
 */
@RestController
@RequestMapping("/api/files/convert")
@RequiredArgsConstructor
public class DecodedFileController {

    private static final Logger logger = LogManager.getLogger(DecodedFileController.class);

    private final FileStorageFacade fileStorageFacade;

    @PostMapping("/save-decoded")
    public ResponseEntity<DecodedFileSaveResponse> decodeAndSaveFile(
            @RequestBody @Valid Base64SaveRequest request) {
        try {
            DecodedFileResult result = fileStorageFacade.decodeAndSave(request);

            logger.info("Successfully decoded and saved file: {} with metadata", result.fileName);

            DecodedFileSaveResponse response = DecodedFileSaveResponse.builder()
                    .success(true)
                    .message("File decoded and saved successfully")
                    .fileName(result.fileName)
                    .originalFileName(result.originalFileName)
                    .downloadLink(result.downloadLink)
                    .fileSize(result.fileSize)
                    .fileSizeBytes(result.fileSizeBytes)
                    .mimeType(result.mimeType)
                    .metadataFile(result.metadataFile)
                    .savedAt(result.savedAt)
                    .build();

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.error("Invalid Base64 content: {}", e.getMessage());
            DecodedFileSaveResponse response = DecodedFileSaveResponse.builder()
                    .success(false)
                    .message("Invalid Base64 content: " + e.getMessage())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (IOException e) {
            logger.error("Error saving decoded file: {}", e.getMessage(), e);
            DecodedFileSaveResponse response = DecodedFileSaveResponse.builder()
                    .success(false)
                    .message("Error saving file: " + e.getMessage())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage(), e);
            DecodedFileSaveResponse response = DecodedFileSaveResponse.builder()
                    .success(false)
                    .message("Unexpected error: " + e.getMessage())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/download-decoded/{fileName}")
    public ResponseEntity<byte[]> downloadDecodedFile(@PathVariable String fileName) {
        try {
            byte[] fileContent = fileStorageFacade.readDecodedFile(fileName);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileContent.length))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(fileContent);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (NoSuchFileException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            logger.error("Error downloading file {}: {}", fileName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Lists decoded files (POST /save-decoded output) so a caller can discover a file without
     * already knowing its exact generated name — the same capability GET /list already provides
     * for .b64 files. Filesystem scanning/identification logic lives entirely in
     * {@code FileStorageFacade.listDecodedFiles}; this method only shapes the HTTP response.
     */
    @GetMapping("/list-decoded")
    public ResponseEntity<List<DecodedFileInfo>> listDecodedFiles() {
        try {
            return ResponseEntity.ok(fileStorageFacade.listDecodedFiles());
        } catch (IOException e) {
            logger.error("Error listing decoded files: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/metadata/{fileName}")
    public ResponseEntity<String> getFileMetadata(@PathVariable String fileName) {
        try {
            String metadata = fileStorageFacade.readMetadata(fileName);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(metadata);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (NoSuchFileException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            logger.error("Error reading metadata for {}: {}", fileName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
