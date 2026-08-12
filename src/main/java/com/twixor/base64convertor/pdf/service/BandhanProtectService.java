package com.twixor.base64convertor.pdf.service;

import com.twixor.base64convertor.pdf.dto.BandhanProtectRequest;
import com.twixor.base64convertor.pdf.exception.PdfProtectionDisabledException;
import com.twixor.base64convertor.pdf.exception.PdfProtectionValidationException;
import com.twixor.base64convertor.pdf.model.BandhanProtectResult;

/**
 * Generates a protected PDF from Base64 statement content, with best-effort extraction of
 * statement fields (statement date, total/minimum amount due, payment due date). Backs
 * {@code POST /api/files/convert/pdf/bandhan/protect}.
 *
 * <p>Reuses the same validation, exception vocabulary, and AES-256 PDFBox protection approach
 * as {@link PdfProtectionService} — this is a sibling capability, not a divergent one.
 */
public interface BandhanProtectService {

    /**
     * @return the resulting PDF bytes (password-protected if {@code request.passwordProtected}
     *         is true, otherwise the decoded PDF unchanged) plus whichever statement fields
     *         could be extracted (individually null when not found — extraction never fails
     *         this call)
     * @throws PdfProtectionValidationException on any input-validation failure (missing
     *         password, invalid Base64, invalid/corrupted PDF, file too large) — maps to HTTP 400
     * @throws PdfProtectionDisabledException if the feature is disabled via configuration —
     *         maps to HTTP 503
     */
    BandhanProtectResult generateProtectedStatement(BandhanProtectRequest request);
}
