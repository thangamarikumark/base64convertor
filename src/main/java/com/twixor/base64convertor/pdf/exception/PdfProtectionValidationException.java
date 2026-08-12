package com.twixor.base64convertor.pdf.exception;

/**
 * Thrown for any client-input validation failure on POST /api/files/pdf/protect
 * (missing password, invalid Base64, invalid/corrupted PDF, file too large).
 * Caught by the controller and mapped to HTTP 400, matching this codebase's established
 * pattern of specific, locally-caught exception types rather than a global handler (ADR-003).
 */
public class PdfProtectionValidationException extends RuntimeException {
    public PdfProtectionValidationException(String message) {
        super(message);
    }
}
