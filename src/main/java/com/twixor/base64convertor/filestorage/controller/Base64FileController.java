package com.twixor.base64convertor.filestorage.controller;

import com.twixor.base64convertor.filestorage.dto.FileInfo;
import com.twixor.base64convertor.filestorage.facade.FileStorageFacade;
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
 * List/download/content/delete endpoints for .b64 output files, split out of the former
 * FileRetrievalController (Phase A, A1). URLs, request/response DTOs, status codes unchanged.
 */
@RestController
@RequestMapping("/api/files/convert")
@RequiredArgsConstructor
public class Base64FileController {

    private static final Logger logger = LogManager.getLogger(Base64FileController.class);

    private final FileStorageFacade fileStorageFacade;

    @GetMapping("/list")
    public ResponseEntity<List<FileInfo>> listBase64Files() {
        try {
            return ResponseEntity.ok(fileStorageFacade.listBase64Files());
        } catch (IOException e) {
            logger.error("Error listing Base64 files: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/download/{fileName}")
    public ResponseEntity<String> downloadFile(@PathVariable String fileName) {
        try {
            String content = fileStorageFacade.readBase64File(fileName);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(content);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (NoSuchFileException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            logger.error("Error downloading file {}: {}", fileName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/content/{fileName}")
    public ResponseEntity<String> getFileContent(@PathVariable String fileName) {
        try {
            return ResponseEntity.ok(fileStorageFacade.readBase64File(fileName));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (NoSuchFileException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            logger.error("Error reading file {}: {}", fileName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{fileName}")
    public ResponseEntity<String> deleteFile(@PathVariable String fileName) {
        try {
            if (fileStorageFacade.deleteBase64File(fileName)) {
                return ResponseEntity.ok("File deleted successfully");
            }
            return ResponseEntity.notFound().build();
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IOException e) {
            logger.error("Error deleting file {}: {}", fileName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
