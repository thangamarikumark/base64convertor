package com.twixor.base64convertor.pdf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request for POST /api/files/convert/pdf/bandhan/protect. Same shape/validation as
 * {@link PdfProtectionRequest} — kept as its own class rather than reused so the two endpoints'
 * contracts can evolve independently (e.g. if Bandhan later needs fields the generic PDF
 * protection endpoint never will, or vice versa).
 *
 * <p>{@code password} is conditionally required: mandatory when {@code passwordProtected} is
 * {@code true}, ignored otherwise — enforced in the service layer, same as
 * {@link PdfProtectionRequest}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to convert a Base64 PDF statement into a protected PDF with "
        + "extracted statement fields, optionally password-protected.")
public class BandhanProtectRequest {

    @NotNull(message = "passwordProtected is required")
    @Schema(description = "Whether to apply password protection to the returned PDF.", example = "true")
    private Boolean passwordProtected;

    @Schema(description = "Password to apply. Mandatory when passwordProtected=true, ignored otherwise.",
            example = "Welcome@123")
    private String password;

    @NotBlank(message = "base64DocContent is required")
    @Schema(description = "Base64-encoded PDF statement content.", example = "JVBERi0xLjQKJ....")
    private String base64DocContent;

    /**
     * Defaults to {@link ResponseType#BASE64}, matching {@link PdfProtectionRequest}'s default
     * rationale: extracted statement fields are only carried in the JSON {@code ApiResponse}
     * envelope, so BASE64 is the shape that actually delivers this endpoint's full contract.
     * ATTACHMENT remains available for callers that only want the binary PDF.
     */
    @Builder.Default
    @Schema(description = "Response shape: ATTACHMENT for a binary PDF download (statement "
            + "fields are not included), BASE64 for a JSON response carrying Base64 content "
            + "plus the extracted statement fields. Defaults to BASE64.",
            example = "BASE64", allowableValues = {"ATTACHMENT", "BASE64"})
    private ResponseType responseType = ResponseType.BASE64;
}
