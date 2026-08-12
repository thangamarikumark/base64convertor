package com.twixor.base64convertor.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Internal result of {@link com.twixor.base64convertor.pdf.service.BandhanProtectService} —
 * the protected PDF's raw bytes plus its best-effort-extracted statement fields. Kept separate
 * from {@link com.twixor.base64convertor.pdf.dto.BandhanProtectResponse} because the controller,
 * not the service, decides how bytes are represented (Base64 string in the JSON envelope vs. a
 * raw binary attachment body) — same division of responsibility as
 * {@code PdfProtectionServiceImpl} returning {@code byte[]} for
 * {@code PdfProtectionController} to encode.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BandhanProtectResult {

    private byte[] pdfBytes;
    private String statementDate;
    private String totalAmountDue;
    private String minimumAmountDue;
    private String paymentDueDate;
}
