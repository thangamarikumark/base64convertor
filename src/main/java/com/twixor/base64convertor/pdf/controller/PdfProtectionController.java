package com.twixor.base64convertor.pdf.controller;

import com.twixor.base64convertor.common.dto.ApiResponse;
import com.twixor.base64convertor.pdf.config.PdfProtectionProperties;
import com.twixor.base64convertor.pdf.dto.PdfProtectionRequest;
import com.twixor.base64convertor.pdf.dto.PdfProtectionResponse;
import com.twixor.base64convertor.pdf.dto.ResponseType;
import com.twixor.base64convertor.pdf.exception.PdfProtectionDisabledException;
import com.twixor.base64convertor.pdf.exception.PdfProtectionValidationException;
import com.twixor.base64convertor.pdf.service.PdfProtectionService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

/**
 * POST /api/files/pdf/protect — converts a Base64-encoded PDF into a protected PDF, returned in
 * one of two shapes selected by the request-body field {@code responseType}
 * ({@link ResponseType#BASE64}, the default — a JSON {@link ApiResponse} envelope carrying
 * Base64 content — or {@link ResponseType#ATTACHMENT} — a binary PDF download).
 *
 * <p>{@code responseType} is an additive, backward-compatible field: existing callers that omit
 * it keep getting exactly what this endpoint has always returned (BASE64/JSON). See
 * {@link PdfProtectionRequest#getResponseType()} for why the default is BASE64, not ATTACHMENT.
 *
 * <p><b>Replaces</b> the earlier name/dob-derived-password contract that previously lived at
 * this same URL — an intentional, approved breaking change. See
 * docs/PDF_Protect_Endpoint_Replacement_Notes.md.
 */
@Validated
@RestController
@RequestMapping("/api/files/convert/pdf")
public class PdfProtectionController {

    private static final Logger logger = LogManager.getLogger(PdfProtectionController.class);

    private final PdfProtectionService pdfProtectionService;
    private final PdfProtectionProperties properties;

    public PdfProtectionController(PdfProtectionService pdfProtectionService, PdfProtectionProperties properties) {
        this.pdfProtectionService = pdfProtectionService;
        this.properties = properties;
        logger.info("PdfProtectionController initialized.");
    }

    @Operation(
            summary = "Convert a Base64 PDF into a protected PDF, as Base64 JSON or a binary attachment",
            description = "Decodes base64DocContent, validates it is a genuine PDF, optionally applies "
                    + "AES-256 password protection via Apache PDFBox, and returns the result either as "
                    + "a JSON ApiResponse envelope carrying Base64 (responseType=BASE64, the default) or "
                    + "as a binary application/pdf download (responseType=ATTACHMENT)."
    )
    @PostMapping("/protect")
    public ResponseEntity<?> protectPdf(@RequestBody @Valid PdfProtectionRequest request) {
        try {
            byte[] pdfBytes = pdfProtectionService.generateProtectedPdf(request);
            String fileName = properties.getDefaultFilename();
            ResponseType responseType = request.getResponseType() != null
                    ? request.getResponseType() : ResponseType.BASE64;

            if (responseType == ResponseType.ATTACHMENT) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(pdfBytes);
            }

            PdfProtectionResponse data = PdfProtectionResponse.builder()
                    .fileName(fileName)
                    .base64ProtectedPdf(Base64.getEncoder().encodeToString(pdfBytes))
                    .build();
            return ResponseEntity.ok(ApiResponse.success("PDF generated successfully", data));

        } catch (PdfProtectionValidationException e) {
            logger.warn("PDF protection validation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));

        } catch (PdfProtectionDisabledException e) {
            logger.warn("PDF protection endpoint called while disabled: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error(e.getMessage()));

        } catch (Exception e) {
            logger.error("Error generating protected PDF: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Internal processing failure while generating PDF"));
        }
    }
}
