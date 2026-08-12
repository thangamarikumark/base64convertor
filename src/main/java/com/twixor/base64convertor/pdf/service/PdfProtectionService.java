package com.twixor.base64convertor.pdf.service;

import com.twixor.base64convertor.pdf.dto.PdfProtectionRequest;
import com.twixor.base64convertor.pdf.exception.PdfProtectionDisabledException;
import com.twixor.base64convertor.pdf.exception.PdfProtectionValidationException;

/**
 * Generates a PDF from Base64 content, optionally applying password protection.
 * Backs {@code POST /api/files/pdf/protect}.
 */
public interface PdfProtectionService {

    /**
     * @return the resulting PDF bytes (password-protected if {@code request.passwordProtected}
     *         is true, otherwise the decoded PDF unchanged)
     * @throws PdfProtectionValidationException on any input-validation failure (missing
     *         password, invalid Base64, invalid/corrupted PDF, file too large) — maps to HTTP 400
     * @throws PdfProtectionDisabledException if the feature is disabled via configuration —
     *         maps to HTTP 503
     */
    byte[] generateProtectedPdf(PdfProtectionRequest request);
}
