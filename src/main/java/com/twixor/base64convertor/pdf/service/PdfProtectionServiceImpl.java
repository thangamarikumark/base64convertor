package com.twixor.base64convertor.pdf.service;

import com.twixor.base64convertor.common.validation.Base64FileValidator;
import com.twixor.base64convertor.pdf.config.PdfProtectionProperties;
import com.twixor.base64convertor.pdf.dto.PdfProtectionRequest;
import com.twixor.base64convertor.pdf.exception.PdfProtectionDisabledException;
import com.twixor.base64convertor.pdf.exception.PdfProtectionValidationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Responsibilities: Base64 decoding, PDF (magic-byte) validation, size-limit enforcement,
 * optional password application via PDFBox, and error handling — all input-validation
 * failures are raised as {@link PdfProtectionValidationException} (-&gt; HTTP 400) so the
 * controller's mapping stays a simple, explicit catch, matching this codebase's established
 * per-controller exception handling style (no global exception handler).
 */
@Service
public class PdfProtectionServiceImpl implements PdfProtectionService {

    private static final Logger log = LogManager.getLogger(PdfProtectionServiceImpl.class);
    private static final int ENCRYPTION_KEY_LENGTH_BITS = 256;

    private final PdfProtectionProperties properties;
    private final MeterRegistry meterRegistry;

    private Counter successCounter;
    private Counter failureCounter;
    private Timer generationTimer;

    public PdfProtectionServiceImpl(PdfProtectionProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initMetrics() {
        successCounter = Counter.builder("pdf.protection.generate")
                .tag("status", "success")
                .description("Number of PDFs successfully generated via /pdf/protect")
                .register(meterRegistry);
        failureCounter = Counter.builder("pdf.protection.generate")
                .tag("status", "failure")
                .description("Number of PDF generation failures via /pdf/protect")
                .register(meterRegistry);
        generationTimer = Timer.builder("pdf.protection.generate.duration")
                .description("Time taken to generate (and optionally protect) a PDF via /pdf/protect")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Override
    public byte[] generateProtectedPdf(PdfProtectionRequest request) {
        if (!properties.isEnabled()) {
            throw new PdfProtectionDisabledException("PDF protection is currently disabled");
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            boolean passwordProtected = Boolean.TRUE.equals(request.getPasswordProtected());
            if (passwordProtected && (request.getPassword() == null || request.getPassword().isBlank())) {
                throw new PdfProtectionValidationException(
                        "Password is mandatory when password protection is enabled");
            }

            byte[] pdfBytes = decodeAndValidate(request.getBase64DocContent());

            byte[] result = passwordProtected ? protect(pdfBytes, request.getPassword()) : pdfBytes;

            successCounter.increment();
            return result;

        } catch (PdfProtectionValidationException e) {
            failureCounter.increment();
            throw e;
        } catch (Exception e) {
            failureCounter.increment();
            log.error("Unexpected error generating PDF: {}", e.getMessage(), e);
            throw new RuntimeException("Internal processing failure while generating PDF", e);
        } finally {
            sample.stop(generationTimer);
        }
    }

    /** Decodes Base64, validates the "%PDF-" header, and enforces the configured size limit. */
    private byte[] decodeAndValidate(String base64DocContent) {
        byte[] pdfBytes;
        try {
            pdfBytes = Base64.getDecoder().decode(base64DocContent);
        } catch (IllegalArgumentException e) {
            throw new PdfProtectionValidationException("Invalid Base64 content: " + e.getMessage());
        }

        long maxBytes = properties.getMaxFileSizeMb() * 1024L * 1024L;
        if (pdfBytes.length > maxBytes) {
            throw new PdfProtectionValidationException(
                    "File exceeds configured maximum size of " + properties.getMaxFileSizeMb() + " MB");
        }

        if (!Base64FileValidator.isValidDecodedForMime(pdfBytes, "application/pdf")) {
            throw new PdfProtectionValidationException("Provided content is not a valid PDF document");
        }

        return pdfBytes;
    }

    /** Applies PDFBox password protection at a fixed 256-bit (AES) encryption strength. */
    private byte[] protect(byte[] pdfBytes, String password) {
        PDDocument document;
        try {
            document = Loader.loadPDF(pdfBytes);
        } catch (InvalidPasswordException e) {
            throw new PdfProtectionValidationException("Source PDF is already password protected");
        } catch (IOException e) {
            throw new PdfProtectionValidationException("PDF is corrupted or could not be parsed");
        }

        try {
            AccessPermission accessPermission = new AccessPermission();
            StandardProtectionPolicy protectionPolicy =
                    new StandardProtectionPolicy(password, password, accessPermission);
            protectionPolicy.setEncryptionKeyLength(ENCRYPTION_KEY_LENGTH_BITS);
            document.protect(protectionPolicy);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            log.info("PDF protected ({} bytes -> {} bytes)", pdfBytes.length, out.size());
            return out.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Failed to apply password protection", e);
        } finally {
            try {
                document.close();
            } catch (IOException ignored) {
                // best-effort close
            }
        }
    }
}
