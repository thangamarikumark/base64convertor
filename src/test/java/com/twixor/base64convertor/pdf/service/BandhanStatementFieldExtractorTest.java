package com.twixor.base64convertor.pdf.service;

import com.twixor.base64convertor.pdf.config.BandhanFieldPatternsProperties;
import com.twixor.base64convertor.pdf.util.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BandhanStatementFieldExtractor}, covering the scoping behavior added
 * while fixing extraction against a real Bandhan Bank statement: the header section (statement
 * date, amounts, due date) is interleaved with unrelated fields (CIF, Primary Card Number,
 * GSTIN), and the same labels legitimately repeat later in the document (a transaction-table
 * column heading, and page-2 illustrative examples). {@link LabeledFieldExtractorTest} covers
 * the line-matching primitives directly; these tests exercise the end-to-end scoping policy
 * that sits on top of them.
 */
class BandhanStatementFieldExtractorTest {

    /** Reproduces the real statement's text-extraction order, including the fields that broke the previous implementation. */
    private static final String REAL_STATEMENT_LAYOUT = String.join("\n",
            "June 2026 statement for",
            "ANIRUDH RAJA GOPAL",
            "ANIRUDH.RAJAGOXXX@XXXDHANBANK.COM",
            "Statement Date: June 15, 2026",
            "Minimum Amount Due: Rs. 0.01",
            "Payment Due Date: June 17, 2026",
            "CIF: 300224249",
            "Primary Card Number : 356171XXXXXX3688",
            "GSTIN : 19AAGCB1323G2ZZ",
            "Total Amount Due : Rs. 0.01",
            "Previous Balance Payments/Credits Purchase/Charges Cash Withdrawal Total Amount Due",
            "r0.00 r0.00 r0.01 r0.00 R0.01",
            "Statement Summary",
            "Card Summary",
            "Transaction details for period November 16, 2023 to June 15, 2026",
            "Total Amount Due on statement dated Dec 10 3000.00",
            "Minimum Amount Due on statement dated Dec 10 150.00"
    );

    private BandhanStatementFieldExtractor extractorFor(String text) {
        PdfTextExtractor fakeTextExtractor = new PdfTextExtractor() {
            @Override
            public String extractText(byte[] pdfBytes) {
                return text;
            }
        };
        return new BandhanStatementFieldExtractor(fakeTextExtractor, new BandhanFieldPatternsProperties());
    }

    @Test
    void realStatementLayout_extractsAllFourFieldsCorrectly() {
        Map<String, String> fields = extractorFor(REAL_STATEMENT_LAYOUT).extractFields(new byte[0]);

        assertEquals("June 15, 2026", fields.get("statementDate"));
        assertEquals("Rs. 0.01", fields.get("totalAmountDue"));
        assertEquals("Rs. 0.01", fields.get("minimumAmountDue"));
        assertEquals("June 17, 2026", fields.get("paymentDueDate"));
    }

    @Test
    void unrelatedIntermediateFields_doNotLeakIntoPaymentDueDateValue() {
        // Regression guard for the actual reported bug: CIF/Primary Card Number/GSTIN sit
        // between "Payment Due Date" and "Total Amount Due" in the real document.
        Map<String, String> fields = extractorFor(REAL_STATEMENT_LAYOUT).extractFields(new byte[0]);
        assertEquals("June 17, 2026", fields.get("paymentDueDate"));
        assertTrue(fields.get("paymentDueDate").length() < 20, "value should not swallow CIF/Primary Card Number/GSTIN lines");
    }

    @Test
    void repeatedLabelsAfterSectionBoundary_areNotMatched() {
        // "Total Amount Due" / "Minimum Amount Due" reappear after "Statement Summary" with
        // different (wrong, for our purposes) values — the boundary must exclude them so the
        // header-section values win regardless of scan order.
        Map<String, String> fields = extractorFor(REAL_STATEMENT_LAYOUT).extractFields(new byte[0]);
        assertEquals("Rs. 0.01", fields.get("totalAmountDue"));
        assertEquals("Rs. 0.01", fields.get("minimumAmountDue"));
    }

    @Test
    void maxScanLines_excludesLabelsBeyondTheConfiguredLimit() {
        BandhanFieldPatternsProperties properties = new BandhanFieldPatternsProperties();
        properties.setMaxScanLines(2); // only "June 2026 statement for" / "ANIRUDH RAJA GOPAL"
        PdfTextExtractor fakeTextExtractor = new PdfTextExtractor() {
            @Override
            public String extractText(byte[] pdfBytes) {
                return REAL_STATEMENT_LAYOUT;
            }
        };
        BandhanStatementFieldExtractor extractor = new BandhanStatementFieldExtractor(fakeTextExtractor, properties);

        Map<String, String> fields = extractor.extractFields(new byte[0]);

        assertTrue(fields.isEmpty());
    }

    @Test
    void labelValueOnNextLineLayout_isSupported() {
        String text = String.join("\n",
                "Statement Date",
                "June 15, 2026",
                "Minimum Amount Due :",
                "Rs. 0.01",
                "Payment Due Date: June 17, 2026",
                "Total Amount Due: Rs. 0.01"
        );
        Map<String, String> fields = extractorFor(text).extractFields(new byte[0]);

        assertEquals("June 15, 2026", fields.get("statementDate"));
        assertEquals("Rs. 0.01", fields.get("minimumAmountDue"));
        assertEquals("June 17, 2026", fields.get("paymentDueDate"));
        assertEquals("Rs. 0.01", fields.get("totalAmountDue"));
    }

    @Test
    void missingLabel_isNullInResultNotAnException() {
        String text = "Statement Date: June 15, 2026";
        Map<String, String> fields = extractorFor(text).extractFields(new byte[0]);

        assertEquals("June 15, 2026", fields.get("statementDate"));
        assertNull(fields.get("totalAmountDue"));
    }

    @Test
    void pdfTextExtractionFailure_returnsEmptyMapNotException() {
        PdfTextExtractor throwingExtractor = new PdfTextExtractor() {
            @Override
            public String extractText(byte[] pdfBytes) throws IOException {
                throw new IOException("corrupted PDF");
            }
        };
        BandhanStatementFieldExtractor extractor =
                new BandhanStatementFieldExtractor(throwingExtractor, new BandhanFieldPatternsProperties());

        Map<String, String> fields = extractor.extractFields(new byte[0]);

        assertTrue(fields.isEmpty());
    }
}
