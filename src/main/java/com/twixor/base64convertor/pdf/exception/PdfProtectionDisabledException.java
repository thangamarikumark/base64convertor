package com.twixor.base64convertor.pdf.exception;

/**
 * Thrown when POST /api/files/pdf/protect is called while {@code pdf.protection.enabled=false}.
 * Caught by the controller and mapped to HTTP 503.
 */
public class PdfProtectionDisabledException extends RuntimeException {
    public PdfProtectionDisabledException(String message) {
        super(message);
    }
}
