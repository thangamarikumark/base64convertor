package com.twixor.base64convertor.pdf.controller;

import com.twixor.base64convertor.pdf.dto.PdfRequest;
import com.twixor.base64convertor.pdf.dto.PdfResponse;
import com.twixor.base64convertor.pdf.facade.PdfDeliveryFacade;
import jakarta.validation.Valid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * "Fetch a file and relay it to a target system" endpoints, split out of the former
 * PdfController (Phase A, A2). Thin — orchestration lives in PdfDeliveryFacade; this
 * controller's only job is mapping the facade's outcome to the exact same status
 * codes/response shapes the original inline implementation produced.
 * URLs, DTOs, and status codes unchanged.
 */
@Validated
@RestController
@RequestMapping("/api/files/convert/pdf")
public class PdfDeliveryController {

    private static final Logger logger = LogManager.getLogger(PdfDeliveryController.class);

    @Value("${app.default.auth.token}")
    private String defaultToken;

    private final PdfDeliveryFacade pdfDeliveryFacade;

    public PdfDeliveryController(PdfDeliveryFacade pdfDeliveryFacade) {
        this.pdfDeliveryFacade = pdfDeliveryFacade;
        logger.info("PdfDeliveryController initialized.");
    }

    @PostMapping(value = "/send")
    public List<PdfResponse> convertFileAndSendToTarget(
            @RequestBody @Valid List<@Valid PdfRequest> requests) {
        List<PdfResponse> results = new ArrayList<>();

        for (PdfRequest req : requests) {
            try {
                PdfDeliveryFacade.DeliveryResult result = pdfDeliveryFacade.deliverAlways(req, defaultToken);
                results.add(new PdfResponse(result.fileName, "SUCCESS", result.base64, result.mimeType));

            } catch (PdfDeliveryFacade.PdfDeliveryException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RestClientException rce) {
                    logger.error("HTTP error for file {}: {}", e.fileName, rce.getMessage(), rce);
                    results.add(new PdfResponse(e.fileName, "FAILED: " + rce.getMessage(), null, e.mimeType));
                } else {
                    logger.error("Error processing request for {}: {}", e.fileName, cause.getMessage(), cause);
                    results.add(new PdfResponse(e.fileName, "FAILED: " + cause.getMessage(), null, e.mimeType));
                }
            }
        }

        return results;
    }

    @PostMapping("/single")
    public ResponseEntity<PdfResponse> convertSingleFile(@RequestBody @Valid PdfRequest req) {
        try {
            PdfDeliveryFacade.DeliveryResult result = pdfDeliveryFacade.deliverIfTargetPresent(req, defaultToken);
            return ResponseEntity.ok(new PdfResponse(result.fileName, "SUCCESS", result.base64, result.mimeType));

        } catch (PdfDeliveryFacade.PdfDeliveryException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RestClientException rce) {
                logger.error("HTTP error for file {}: {}", e.fileName, rce.getMessage(), rce);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(new PdfResponse(e.fileName, "FAILED: " + rce.getMessage(), null, null));
            } else {
                logger.error("Error processing file {}: {}", e.fileName, cause.getMessage(), cause);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new PdfResponse(e.fileName, "FAILED: " + cause.getMessage(), null, null));
            }
        }
    }
}
