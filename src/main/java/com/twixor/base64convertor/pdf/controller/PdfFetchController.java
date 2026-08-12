package com.twixor.base64convertor.pdf.controller;

import com.twixor.base64convertor.common.util.LogSanitizer;
import com.twixor.base64convertor.pdf.dto.PdfBase64Request;
import com.twixor.base64convertor.pdf.dto.PdfBase64RequestDynamic;
import com.twixor.base64convertor.pdf.dto.PdfResponse;
import com.twixor.base64convertor.pdf.service.PdfService;
import jakarta.validation.Valid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

/**
 * "Fetch a file, return its Base64" endpoints, split out of the former PdfController
 * (Phase A, A2). No facade — each method delegates to exactly one PdfService call with no
 * additional controller-side orchestration (see ADR-002). URLs, DTOs, and the existing
 * (intentionally divergent) error-status conventions per endpoint are unchanged.
 */
@Validated
@RestController
@RequestMapping("/api/files/convert/pdf")
public class PdfFetchController {

    private static final Logger logger = LogManager.getLogger(PdfFetchController.class);

    private final PdfService pdfService;

    public PdfFetchController(PdfService pdfService) {
        this.pdfService = pdfService;
        logger.info("PdfFetchController initialized.");
    }

    @PostMapping(value = "/convert/base64")
    public ResponseEntity<PdfResponse> convertToBase64(@RequestBody @Valid PdfBase64Request req) {
        String fileName = "downloaded.pdf";
        String mimeType = "application/pdf";
        try {
            logger.info("Base64 conversion requested for URL: {}", LogSanitizer.sanitizeUrl(req.getUrl()));
            String base64 = pdfService.fetchAndConvertToBase64(req.getUrl(), req.getCookie(), req.getPayload());
            return ResponseEntity.ok(new PdfResponse(fileName, "SUCCESS", base64, mimeType));

        } catch (RestClientException rce) {
            logger.error("HTTP error while fetching file {}: {}", LogSanitizer.sanitizeUrl(req.getUrl()), rce.getMessage(), rce);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new PdfResponse(fileName, "FAILED: " + rce.getMessage(), null, mimeType));
        } catch (Exception e) {
            logger.error("Error converting file {}: {}", LogSanitizer.sanitizeUrl(req.getUrl()), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new PdfResponse(fileName, "FAILED: " + e.getMessage(), null, mimeType));
        }
    }

    @PostMapping(value = "/convert/base64dynamic", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PdfResponse> convertToBase64Dynamic(@RequestBody @Valid PdfBase64RequestDynamic req) {
        logger.info("Dynamic Base64 conversion requested for URL: {}", LogSanitizer.sanitizeUrl(req.getUrl()));
        PdfResponse response = pdfService.fetchAndConvertToBase64Dynamic(req);
        return ResponseEntity.ok(response);
    }
}
