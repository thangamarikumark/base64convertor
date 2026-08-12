package com.twixor.base64convertor.checksum.controller;

import com.twixor.base64convertor.checksum.dto.ChecksumRequest;
import com.twixor.base64convertor.checksum.service.ChecksumService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Moved into the checksum module (Phase A, A3). CRC32 logic extracted into ChecksumService;
 * this controller is now thin. URLs, request/response shapes, and validation unchanged.
 */
@RestController
@RequestMapping("/api/files/convert")
public class ChecksumController {

    private final ChecksumService checksumService;

    public ChecksumController(ChecksumService checksumService) {
        this.checksumService = checksumService;
    }

    @PostMapping("/checksum")
    public ResponseEntity<Map<String, String>> generateChecksum(@RequestBody ChecksumRequest request) {
        String message = request.getMessage();
        String secretKey = request.getSecretKey();

        if (message == null || message.isEmpty() || secretKey == null || secretKey.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "message and secretKey cannot be empty"));
        }

        return ResponseEntity.ok(checksumService.compute(message, secretKey));
    }

    @PostMapping("/checksumgenerator")
    public ResponseEntity<Map<String, String>> generateChecksumFromParams(
            @RequestParam String message,
            @RequestParam String secretKey) {

        if (message == null || message.isEmpty() || secretKey == null || secretKey.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "message and secretKey cannot be empty"));
        }

        return ResponseEntity.ok(checksumService.compute(message, secretKey));
    }
}
