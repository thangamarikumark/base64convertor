package com.twixor.base64convertor.pdf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request for POST /api/files/pdf/protect. Replaces the earlier name/dob-derived-password
 * contract for this same URL (an intentional, approved breaking change).
 *
 * <p>{@code password} is conditionally required: mandatory when {@code passwordProtected}
 * is {@code true}, ignored otherwise. Bean Validation's per-field annotations can't express
 * this cross-field rule, so it is enforced explicitly in the service layer.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to convert a Base64 PDF into a downloadable PDF, optionally password-protected.")
public class PdfProtectionRequest {

    @NotNull(message = "passwordProtected is required")
    @Schema(description = "Whether to apply password protection to the returned PDF.", example = "true")
    private Boolean passwordProtected;

    @Schema(description = "Password to apply. Mandatory when passwordProtected=true, ignored otherwise.",
            example = "Welcome@123")
    private String password;

    @NotBlank(message = "base64DocContent is required")
    @Schema(description = "Base64-encoded PDF document content.", example = "JVBERi0xLjQKJ....")
    private String base64DocContent;

    /**
     * Defaults to {@link ResponseType#BASE64}, <b>not</b> {@link ResponseType#ATTACHMENT} —
     * deliberately deviating from a plain "ATTACHMENT is the default" design, because BASE64 is
     * what this endpoint has actually returned to every existing caller since it was built this
     * session (a JSON {@code ApiResponse} envelope carrying Base64). Defaulting to ATTACHMENT
     * would silently change the response shape for every current caller that omits this new
     * field — the opposite of backward compatible. ATTACHMENT remains fully available as an
     * explicit opt-in for callers that want a direct binary download.
     */
    @Builder.Default
    @Schema(description = "Response shape: ATTACHMENT for a binary PDF download, BASE64 for a "
            + "JSON response carrying Base64 content. Defaults to BASE64 to match this "
            + "endpoint's existing behavior for callers that omit this field.",
            example = "BASE64", allowableValues = {"ATTACHMENT", "BASE64"})
    private ResponseType responseType = ResponseType.BASE64;
}
