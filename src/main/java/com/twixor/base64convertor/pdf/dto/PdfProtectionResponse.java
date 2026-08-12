package com.twixor.base64convertor.pdf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Success payload for POST /api/files/pdf/protect, carried as the {@code data} field of the
 * standard {@link com.twixor.base64convertor.common.dto.ApiResponse} envelope. {@code success}/
 * {@code message} now live on the envelope, not here — see ApiResponse.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Generated PDF payload.")
public class PdfProtectionResponse {

    @Schema(description = "Filename suggested for the generated PDF.", example = "protected_document.pdf")
    private String fileName;

    @Schema(description = "Base64-encoded generated PDF (protected if passwordProtected was true).",
            example = "JVBERi0xLjQKJ....")
    private String base64ProtectedPdf;
}
