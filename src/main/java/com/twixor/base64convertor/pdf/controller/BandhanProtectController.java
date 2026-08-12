package com.twixor.base64convertor.pdf.controller;

import com.twixor.base64convertor.common.dto.ApiResponse;
import com.twixor.base64convertor.pdf.config.PdfProtectionProperties;
import com.twixor.base64convertor.pdf.dto.BandhanProtectRequest;
import com.twixor.base64convertor.pdf.dto.BandhanProtectResponse;
import com.twixor.base64convertor.pdf.dto.ResponseType;
import com.twixor.base64convertor.pdf.exception.PdfProtectionDisabledException;
import com.twixor.base64convertor.pdf.exception.PdfProtectionValidationException;
import com.twixor.base64convertor.pdf.model.BandhanProtectResult;
import com.twixor.base64convertor.pdf.service.BandhanProtectService;
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
 * POST /api/files/convert/pdf/bandhan/protect — converts a Base64-encoded PDF statement into a
 * protected PDF, the same way {@code PdfProtectionController} does, while additionally
 * extracting statement fields (statement date, total/minimum amount due, payment due date) from
 * the document's text content on a best-effort basis. Response shape selection
 * ({@link ResponseType#BASE64} default vs. {@link ResponseType#ATTACHMENT}) and exception-to-
 * HTTP-status mapping are identical to {@link PdfProtectionController} — this is a sibling
 * endpoint, not a divergent contract. Note that extracted statement fields are only carried in
 * the BASE64/JSON response shape; ATTACHMENT remains a plain binary PDF download.
 */
@Validated
@RestController
@RequestMapping("/api/files/convert/pdf/bandhan")
public class BandhanProtectController {

    private static final Logger logger = LogManager.getLogger(BandhanProtectController.class);

    private final BandhanProtectService bandhanProtectService;
    private final PdfProtectionProperties properties;

    public BandhanProtectController(BandhanProtectService bandhanProtectService, PdfProtectionProperties properties) {
        this.bandhanProtectService = bandhanProtectService;
        this.properties = properties;
        logger.info("BandhanProtectController initialized.");
    }

    @Operation(
            summary = "Convert a Base64 PDF statement into a protected PDF with extracted statement fields",
            description = "Decodes base64DocContent, validates it is a genuine PDF, optionally applies "
                    + "AES-256 password protection via Apache PDFBox, best-effort extracts statement date, "
                    + "total amount due, minimum amount due, and payment due date from the document text, "
                    + "and returns the result either as a JSON ApiResponse envelope carrying Base64 plus the "
                    + "extracted fields (responseType=BASE64, the default) or as a binary application/pdf "
                    + "download (responseType=ATTACHMENT, fields not included)."
    )
    @PostMapping("/protect")
    public ResponseEntity<?> protectStatement(@RequestBody @Valid BandhanProtectRequest request) {
        logger.info("Bandhan protect request received");
        try {
            BandhanProtectResult result = bandhanProtectService.generateProtectedStatement(request);
            String fileName = properties.getDefaultFilename();
            ResponseType responseType = request.getResponseType() != null
                    ? request.getResponseType() : ResponseType.BASE64;

            if (responseType == ResponseType.ATTACHMENT) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(result.getPdfBytes());
            }

            BandhanProtectResponse data = BandhanProtectResponse.builder()
                    .fileName(fileName)
                    .base64ProtectedPdf(Base64.getEncoder().encodeToString(result.getPdfBytes()))
                    .statementDate(result.getStatementDate())
                    .totalAmountDue(result.getTotalAmountDue())
                    .minimumAmountDue(result.getMinimumAmountDue())
                    .paymentDueDate(result.getPaymentDueDate())
                    .build();
            return ResponseEntity.ok(ApiResponse.success("PDF generated successfully", data));

        } catch (PdfProtectionValidationException e) {
            logger.warn("Bandhan PDF protection validation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));

        } catch (PdfProtectionDisabledException e) {
            logger.warn("Bandhan PDF protection endpoint called while disabled: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error(e.getMessage()));

        } catch (Exception e) {
            logger.error("Error generating Bandhan protected PDF: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Internal processing failure while generating PDF"));
        }
    }
}
