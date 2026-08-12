package com.twixor.base64convertor.pdf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response shape selector for POST /api/files/pdf/protect. Additive request-body field —
 * see {@link PdfProtectionRequest#getResponseType()} for the backward-compatibility rationale
 * behind defaulting to {@link #BASE64} rather than {@link #ATTACHMENT}.
 */
@Schema(description = "Desired response shape for the generated PDF.",
        allowableValues = {"ATTACHMENT", "BASE64"})
public enum ResponseType {

    /** Binary PDF response with {@code Content-Type: application/pdf} and a
     *  {@code Content-Disposition: attachment} header — for direct browser/Postman downloads. */
    ATTACHMENT,

    /** JSON response ({@link com.twixor.base64convertor.common.dto.ApiResponse} envelope)
     *  carrying the PDF as a Base64 string — for programmatic consumers (mobile apps, other
     *  APIs, workflow engines, document-delivery integrations). */
    BASE64
}
