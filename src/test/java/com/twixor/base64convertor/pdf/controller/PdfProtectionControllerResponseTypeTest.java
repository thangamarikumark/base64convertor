package com.twixor.base64convertor.pdf.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.twixor.base64convertor.common.config.ApiMetadataProperties;
import com.twixor.base64convertor.pdf.config.PdfProtectionProperties;
import com.twixor.base64convertor.pdf.dto.PdfProtectionRequest;
import com.twixor.base64convertor.pdf.exception.PdfProtectionValidationException;
import com.twixor.base64convertor.pdf.service.PdfProtectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the additive {@code responseType} request field on POST /api/files/pdf/protect:
 * BASE64 (default, JSON envelope) and ATTACHMENT (binary download) both work, an omitted
 * field preserves the endpoint's pre-existing BASE64 behavior, and an invalid enum value is
 * rejected as 400 (not 500) — Spring's default request-body-unreadable handling, verified here
 * rather than assumed, since this codebase has no global @ControllerAdvice to fall back on.
 */
@WebMvcTest(PdfProtectionController.class)
@Import(ApiMetadataProperties.class)
class PdfProtectionControllerResponseTypeTest {

    private static final byte[] FAKE_PDF_BYTES = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);
    private static final String SAMPLE_BASE64_PDF = Base64.getEncoder().encodeToString(FAKE_PDF_BYTES);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PdfProtectionService pdfProtectionService;

    @MockBean
    private PdfProtectionProperties properties;

    @BeforeEach
    void setUp() {
        when(properties.getDefaultFilename()).thenReturn("protected_document.pdf");
    }

    @Test
    void responseTypeBase64_returnsJsonEnvelopeWithBase64Content() throws Exception {
        when(pdfProtectionService.generateProtectedPdf(any())).thenReturn(FAKE_PDF_BYTES);

        PdfProtectionRequest request = PdfProtectionRequest.builder()
                .passwordProtected(false)
                .base64DocContent(SAMPLE_BASE64_PDF)
                .responseType(com.twixor.base64convertor.pdf.dto.ResponseType.BASE64)
                .build();

        mockMvc.perform(post("/api/files/pdf/protect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.fileName").value("protected_document.pdf"))
                .andExpect(jsonPath("$.data.base64ProtectedPdf").value(
                        Base64.getEncoder().encodeToString(FAKE_PDF_BYTES)));
    }

    @Test
    void responseTypeOmitted_defaultsToBase64Json_forBackwardCompatibility() throws Exception {
        when(pdfProtectionService.generateProtectedPdf(any())).thenReturn(FAKE_PDF_BYTES);

        String requestJsonWithoutResponseType = """
                {"passwordProtected": false, "base64DocContent": "%s"}
                """.formatted(SAMPLE_BASE64_PDF);

        mockMvc.perform(post("/api/files/pdf/protect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJsonWithoutResponseType))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.base64ProtectedPdf").exists());
    }

    @Test
    void responseTypeAttachment_returnsBinaryPdfWithDownloadHeaders() throws Exception {
        when(pdfProtectionService.generateProtectedPdf(any())).thenReturn(FAKE_PDF_BYTES);

        PdfProtectionRequest request = PdfProtectionRequest.builder()
                .passwordProtected(false)
                .base64DocContent(SAMPLE_BASE64_PDF)
                .responseType(com.twixor.base64convertor.pdf.dto.ResponseType.ATTACHMENT)
                .build();

        mockMvc.perform(post("/api/files/pdf/protect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"protected_document.pdf\""))
                .andExpect(content().bytes(FAKE_PDF_BYTES));
    }

    @Test
    void invalidResponseTypeValue_isRejectedAs400_notServerError() throws Exception {
        String requestJsonWithInvalidResponseType = """
                {"passwordProtected": false, "base64DocContent": "%s", "responseType": "NOT_A_REAL_TYPE"}
                """.formatted(SAMPLE_BASE64_PDF);

        mockMvc.perform(post("/api/files/pdf/protect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJsonWithInvalidResponseType))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validationFailure_stillReturnsJsonError_regardlessOfResponseType() throws Exception {
        when(pdfProtectionService.generateProtectedPdf(any()))
                .thenThrow(new PdfProtectionValidationException("Provided content is not a valid PDF document"));

        PdfProtectionRequest request = PdfProtectionRequest.builder()
                .passwordProtected(false)
                .base64DocContent(SAMPLE_BASE64_PDF)
                .responseType(com.twixor.base64convertor.pdf.dto.ResponseType.ATTACHMENT)
                .build();

        mockMvc.perform(post("/api/files/pdf/protect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }
}
