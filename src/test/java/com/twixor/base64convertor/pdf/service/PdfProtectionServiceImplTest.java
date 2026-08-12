package com.twixor.base64convertor.pdf.service;

import com.twixor.base64convertor.pdf.config.PdfProtectionProperties;
import com.twixor.base64convertor.pdf.dto.PdfProtectionRequest;
import com.twixor.base64convertor.pdf.exception.PdfProtectionDisabledException;
import com.twixor.base64convertor.pdf.exception.PdfProtectionValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PdfProtectionServiceImplTest {

    private static byte[] samplePdfBytes;
    private static String samplePdfBase64;

    private PdfProtectionProperties properties;
    private PdfProtectionServiceImpl service;

    @BeforeAll
    static void buildSamplePdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            samplePdfBytes = out.toByteArray();
        }
        samplePdfBase64 = Base64.getEncoder().encodeToString(samplePdfBytes);
    }

    @BeforeEach
    void setUp() {
        properties = new PdfProtectionProperties();
        properties.setEnabled(true);
        properties.setMaxFileSizeMb(20);
        properties.setDefaultFilename("protected_document.pdf");
        service = new PdfProtectionServiceImpl(properties, new SimpleMeterRegistry());
        service.initMetrics();
    }

    @Test
    void passwordNotProtected_returnsPdfUnchanged() {
        PdfProtectionRequest request = PdfProtectionRequest.builder()
                .passwordProtected(false)
                .base64DocContent(samplePdfBase64)
                .build();

        byte[] result = service.generateProtectedPdf(request);

        assertArrayEquals(samplePdfBytes, result);
    }

    @Test
    void passwordProtected_returnsPdfOpenableWithThatPassword() throws IOException {
        PdfProtectionRequest request = PdfProtectionRequest.builder()
                .passwordProtected(true)
                .password("Welcome@123")
                .base64DocContent(samplePdfBase64)
                .build();

        byte[] result = service.generateProtectedPdf(request);

        assertNotEquals(samplePdfBytes.length, 0);
        assertTrue(new String(result, 0, 5).startsWith("%PDF-"));

        // Correct password opens it
        try (PDDocument opened = Loader.loadPDF(result, "Welcome@123")) {
            assertEquals(1, opened.getNumberOfPages());
        }

        // Wrong password is rejected
        assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(result, "wrong-password").close());
    }

    @Test
    void passwordProtectedTrue_blankPassword_throwsValidationException() {
        PdfProtectionRequest request = PdfProtectionRequest.builder()
                .passwordProtected(true)
                .password("   ")
                .base64DocContent(samplePdfBase64)
                .build();

        PdfProtectionValidationException ex = assertThrows(PdfProtectionValidationException.class,
                () -> service.generateProtectedPdf(request));
        assertTrue(ex.getMessage().contains("Password is mandatory"));
    }

    @Test
    void passwordProtectedTrue_nullPassword_throwsValidationException() {
        PdfProtectionRequest request = PdfProtectionRequest.builder()
                .passwordProtected(true)
                .password(null)
                .base64DocContent(samplePdfBase64)
                .build();

        assertThrows(PdfProtectionValidationException.class, () -> service.generateProtectedPdf(request));
    }

    @Test
    void invalidBase64_throwsValidationException() {
        PdfProtectionRequest request = PdfProtectionRequest.builder()
                .passwordProtected(false)
                .base64DocContent("not-valid-base64-!!!")
                .build();

        PdfProtectionValidationException ex = assertThrows(PdfProtectionValidationException.class,
                () -> service.generateProtectedPdf(request));
        assertTrue(ex.getMessage().contains("Invalid Base64"));
    }

    @Test
    void validBase64ButNotAPdf_throwsValidationException() {
        String notAPdfBase64 = Base64.getEncoder().encodeToString("hello world, not a pdf".getBytes());
        PdfProtectionRequest request = PdfProtectionRequest.builder()
                .passwordProtected(false)
                .base64DocContent(notAPdfBase64)
                .build();

        PdfProtectionValidationException ex = assertThrows(PdfProtectionValidationException.class,
                () -> service.generateProtectedPdf(request));
        assertTrue(ex.getMessage().contains("not a valid PDF"));
    }

    @Test
    void fileExceedsConfiguredMaxSize_throwsValidationException() {
        properties.setMaxFileSizeMb(0); // any non-empty PDF now exceeds the limit
        PdfProtectionRequest request = PdfProtectionRequest.builder()
                .passwordProtected(false)
                .base64DocContent(samplePdfBase64)
                .build();

        PdfProtectionValidationException ex = assertThrows(PdfProtectionValidationException.class,
                () -> service.generateProtectedPdf(request));
        assertTrue(ex.getMessage().contains("exceeds configured maximum size"));
    }

    @Test
    void serviceDisabled_throwsDisabledException() {
        properties.setEnabled(false);
        PdfProtectionRequest request = PdfProtectionRequest.builder()
                .passwordProtected(false)
                .base64DocContent(samplePdfBase64)
                .build();

        assertThrows(PdfProtectionDisabledException.class, () -> service.generateProtectedPdf(request));
    }

    @Test
    void alreadyPasswordProtectedSource_throwsValidationException() throws IOException {
        byte[] alreadyProtected;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner", "existing-password", new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
            document.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            alreadyProtected = out.toByteArray();
        }

        PdfProtectionRequest request = PdfProtectionRequest.builder()
                .passwordProtected(true)
                .password("new-password")
                .base64DocContent(Base64.getEncoder().encodeToString(alreadyProtected))
                .build();

        PdfProtectionValidationException ex = assertThrows(PdfProtectionValidationException.class,
                () -> service.generateProtectedPdf(request));
        assertTrue(ex.getMessage().contains("already password protected"));
    }
}
