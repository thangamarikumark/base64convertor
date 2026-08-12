package com.twixor.base64convertor.filestorage.controller;

import com.twixor.base64convertor.filestorage.dto.Base64SaveRequest;
import com.twixor.base64convertor.filestorage.dto.Base64SaveResponse;
import com.twixor.base64convertor.filestorage.facade.FileStorageFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Raw-Base64 storage callback endpoint, split out of the former FileRetrievalController
 * (Phase A, A1). URL, request/response DTOs, status codes unchanged.
 */
@RestController
@RequestMapping("/api/files/convert")
@RequiredArgsConstructor
public class CallbackController {

    private static final Logger logger = LogManager.getLogger(CallbackController.class);

    private final FileStorageFacade fileStorageFacade;

    @PostMapping("/callback")
    public ResponseEntity<Base64SaveResponse> saveBase64File(@RequestBody @Valid Base64SaveRequest request) {
        try {
            return ResponseEntity.ok(fileStorageFacade.saveRawBase64(request));
        } catch (IOException e) {
            logger.error("Error saving Base64 file: {}", e.getMessage(), e);
            Base64SaveResponse response = Base64SaveResponse.builder()
                    .success(false)
                    .message("Error saving file: " + e.getMessage())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage(), e);
            Base64SaveResponse response = Base64SaveResponse.builder()
                    .success(false)
                    .message("Unexpected error: " + e.getMessage())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
