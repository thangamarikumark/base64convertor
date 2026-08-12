package com.twixor.base64convertor.pdf.service;

import com.twixor.base64convertor.common.validation.Base64FileValidator;
import com.twixor.base64convertor.pdf.config.PdfProtectionProperties;
import com.twixor.base64convertor.pdf.dto.BandhanProtectRequest;
import com.twixor.base64convertor.pdf.exception.PdfProtectionDisabledException;
import com.twixor.base64convertor.pdf.exception.PdfProtectionValidationException;
import com.twixor.base64convertor.pdf.model.BandhanProtectResult;
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
import java.util.Map;

/**
 * Responsibilities: Base64 decoding, PDF (magic-byte) validation, size-limit enforcement,
 * best-effort statement field extraction (delegated to {@link BandhanStatementFieldExtractor}),
 * and optional password application via PDFBox — all input-validation failures are raised as
 * {@link PdfProtectionValidationException} (-&gt; HTTP 400), matching
 * {@link PdfProtectionServiceImpl}'s established pattern (no global exception handler, ADR-003).
 *
 * <p>Reuses {@link PdfProtectionProperties} for the enabled kill-switch and max-file-size limit
 * rather than introducing a duplicate config class — the protection mechanism and its limits are
 * identical to the generic {@code /pdf/protect} endpoint; only the response contract (extra
 * statement fields) differs.
 *
 * <p>No extraction logic (no regex, no field-specific code) lives here — that is entirely
 * delegated to {@link BandhanStatementFieldExtractor}, keeping this class focused solely on
 * validate -&gt; extract -&gt; protect -&gt; assemble-result orchestration.
 */
@Service
public class BandhanProtectServiceImpl implements BandhanProtectService {

    private static final Logger log = LogManager.getLogger(BandhanProtectServiceImpl.class);
    private static final int ENCRYPTION_KEY_LENGTH_BITS = 256;

    private final PdfProtectionProperties properties;
    private final BandhanStatementFieldExtractor statementFieldExtractor;

    public BandhanProtectServiceImpl(PdfProtectionProperties properties,
                                      BandhanStatementFieldExtractor statementFieldExtractor) {
        this.properties = properties;
        this.statementFieldExtractor = statementFieldExtractor;
    }

    @Override
    public BandhanProtectResult generateProtectedStatement(BandhanProtectRequest request) {
        if (!properties.isEnabled()) {
            throw new PdfProtectionDisabledException("PDF protection is currently disabled");
        }

        boolean passwordProtected = Boolean.TRUE.equals(request.getPasswordProtected());
        if (passwordProtected && (request.getPassword() == null || request.getPassword().isBlank())) {
            throw new PdfProtectionValidationException(
                    "Password is mandatory when password protection is enabled");
        }

        byte[] pdfBytes = decodeAndValidate(request.getBase64DocContent());
        log.info("Bandhan statement PDF validated ({} bytes)", pdfBytes.length);

        Map<String, String> fields = statementFieldExtractor.extractFields(pdfBytes);

        byte[] resultBytes = passwordProtected ? protect(pdfBytes, request.getPassword()) : pdfBytes;
        log.info("Bandhan statement PDF protected");

        BandhanProtectResult result = BandhanProtectResult.builder()
                .pdfBytes(resultBytes)
                .statementDate(fields.get("statementDate"))
                .totalAmountDue(fields.get("totalAmountDue"))
                .minimumAmountDue(fields.get("minimumAmountDue"))
                .paymentDueDate(fields.get("paymentDueDate"))
                .build();
        log.info("Bandhan protect response generated");
        return result;
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
