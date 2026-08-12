package com.twixor.base64convertor.pdf.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Reusable PDF-to-plain-text utility. Sole responsibility: given decoded PDF bytes, return the
 * document's extracted text via Apache PDFBox. Has no knowledge of what the text will be used
 * for — no Bandhan-specific or field-extraction logic lives here, so any future PDF feature that
 * needs the raw text content of a document can reuse this as-is.
 *
 * <p>Failure handling is deliberately left to the caller: this method throws rather than
 * swallowing errors, so callers that must fail hard on unreadable PDFs can, and callers that
 * want extraction to be best-effort (e.g. statement field extraction, which must never fail the
 * primary PDF-protection request) can catch and degrade gracefully.
 */
@Component
public class PdfTextExtractor {

    /**
     * @param pdfBytes decoded, already-validated PDF content
     * @return the document's text content, as produced by {@link PDFTextStripper}
     * @throws IOException if the PDF cannot be loaded or parsed
     */
    public String extractText(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
