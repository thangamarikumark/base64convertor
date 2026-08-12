package com.twixor.base64convertor.pdf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Success payload for POST /api/files/convert/pdf/bandhan/protect, carried as the {@code data}
 * field of the standard {@link com.twixor.base64convertor.common.dto.ApiResponse} envelope.
 *
 * <p>The four statement fields are extracted best-effort from the source PDF's text content
 * (see {@link com.twixor.base64convertor.pdf.service.BandhanStatementFieldExtractor}) and are
 * {@code null}, individually, when their configured label isn't found in the document —
 * extraction failure never fails the request, since password protection is this endpoint's
 * primary responsibility.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Generated PDF payload plus best-effort extracted statement fields.")
public class BandhanProtectResponse {

    @Schema(description = "Filename suggested for the generated PDF.", example = "protected_statement.pdf")
    private String fileName;

    @Schema(description = "Base64-encoded generated PDF (protected if passwordProtected was true).",
            example = "JVBERi0xLjQKJ....")
    private String base64ProtectedPdf;

    @Schema(description = "Statement date extracted from the document text. Null if not found.",
            example = "June 15, 2026")
    private String statementDate;

    @Schema(description = "Total amount due extracted from the document text. Null if not found.",
            example = "12,345.00")
    private String totalAmountDue;

    @Schema(description = "Minimum amount due extracted from the document text. Null if not found.",
            example = "1,200.00")
    private String minimumAmountDue;

    @Schema(description = "Payment due date extracted from the document text. Null if not found.",
            example = "July 5, 2026")
    private String paymentDueDate;
}
