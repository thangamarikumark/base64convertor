package com.twixor.base64convertor.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LabeledFieldExtractor}, added while fixing Bandhan statement field
 * extraction (see {@code BandhanStatementFieldExtractor}). The previous whole-document,
 * "value = text up to the next known label" implementation broke whenever unrelated fields sat
 * between two target labels (e.g. CIF/Primary Card Number/GSTIN between "Payment Due Date" and
 * "Total Amount Due" on a real Bandhan Bank statement) — these tests pin down the replacement
 * line-based behavior: same-line values, next-line values, optional/absent colons, extra
 * whitespace, first-occurrence-wins on repeated labels, and missing labels.
 */
class LabeledFieldExtractorTest {

    @Test
    void extractAfterLabel_sameLineWithColon() {
        Optional<String> value = LabeledFieldExtractor.extractAfterLabel(
                "Statement Date: June 15, 2026", "Statement Date");
        assertEquals("June 15, 2026", value.orElseThrow());
    }

    @Test
    void extractAfterLabel_sameLineWithSpaceBeforeColon() {
        Optional<String> value = LabeledFieldExtractor.extractAfterLabel(
                "Total Amount Due : Rs. 0.01", "Total Amount Due");
        assertEquals("Rs. 0.01", value.orElseThrow());
    }

    @Test
    void extractAfterLabel_extraWhitespaceAroundValue() {
        Optional<String> value = LabeledFieldExtractor.extractAfterLabel(
                "Statement Date   :      June 15, 2026   ", "Statement Date");
        assertEquals("June 15, 2026", value.orElseThrow());
    }

    @Test
    void extractAfterLabel_labelAloneOnLineReturnsEmpty() {
        assertTrue(LabeledFieldExtractor.extractAfterLabel("Statement Date", "Statement Date").isEmpty());
    }

    @Test
    void extractAfterLabel_labelWithTrailingColonOnlyReturnsEmpty() {
        assertTrue(LabeledFieldExtractor.extractAfterLabel("Statement Date :", "Statement Date").isEmpty());
    }

    @Test
    void extractFirstOccurrence_findsFirstMatchOnlyAmongRepeats() {
        List<String> lines = List.of(
                "Total Amount Due : Rs. 0.01",
                "Cash Withdrawal Total Amount Due",
                "Total Amount Due on statement dated Dec 10 3000.00"
        );
        Optional<Integer> index = LabeledFieldExtractor.extractFirstOccurrence(lines, "Total Amount Due");
        assertEquals(0, index.orElseThrow());
    }

    @Test
    void extractFirstOccurrence_notFoundReturnsEmpty() {
        assertTrue(LabeledFieldExtractor.extractFirstOccurrence(List.of("no labels here"), "Statement Date").isEmpty());
    }

    @Test
    void extractValueFromSameOrNextLine_sameLineValuePreferred() {
        List<String> lines = List.of("Statement Date: June 15, 2026", "Minimum Amount Due: Rs. 0.01");
        Optional<String> value = LabeledFieldExtractor.extractValueFromSameOrNextLine(lines, 0, "Statement Date");
        assertEquals("June 15, 2026", value.orElseThrow());
    }

    @Test
    void extractValueFromSameOrNextLine_fallsBackToNextLineWhenLabelAlone() {
        List<String> lines = List.of("Statement Date", "June 15, 2026");
        Optional<String> value = LabeledFieldExtractor.extractValueFromSameOrNextLine(lines, 0, "Statement Date");
        assertEquals("June 15, 2026", value.orElseThrow());
    }

    @Test
    void extractValueFromSameOrNextLine_fallsBackToNextLineWhenLabelHasTrailingColonOnly() {
        List<String> lines = List.of("Statement Date :", "June 15, 2026");
        Optional<String> value = LabeledFieldExtractor.extractValueFromSameOrNextLine(lines, 0, "Statement Date");
        assertEquals("June 15, 2026", value.orElseThrow());
    }

    @Test
    void extractValueFromSameOrNextLine_noNextLineReturnsEmpty() {
        List<String> lines = List.of("Statement Date");
        assertTrue(LabeledFieldExtractor.extractValueFromSameOrNextLine(lines, 0, "Statement Date").isEmpty());
    }

    @Test
    void toNonBlankLines_trimsAndDropsBlankLines() {
        List<String> lines = LabeledFieldExtractor.toNonBlankLines("  Statement Date: June 15, 2026  \n\n  \nMinimum Amount Due: Rs. 0.01\r\n");
        assertEquals(List.of("Statement Date: June 15, 2026", "Minimum Amount Due: Rs. 0.01"), lines);
    }

    @Test
    void toNonBlankLines_nullOrBlankReturnsEmptyList() {
        assertTrue(LabeledFieldExtractor.toNonBlankLines(null).isEmpty());
        assertTrue(LabeledFieldExtractor.toNonBlankLines("   ").isEmpty());
    }

    /**
     * Reproduces the real Bandhan statement layout that broke the previous implementation:
     * unrelated fields (CIF, Primary Card Number, GSTIN) sit between "Payment Due Date" and
     * "Total Amount Due". The line-based extractor must not let those intervening lines leak
     * into either field's value.
     */
    @Test
    void extract_realStatementLayout_intermediateUnrelatedFieldsDoNotLeakIntoValues() {
        List<String> lines = List.of(
                "Statement Date: June 15, 2026",
                "Minimum Amount Due: Rs. 0.01",
                "Payment Due Date: June 17, 2026",
                "CIF: 300224249",
                "Primary Card Number : 356171XXXXXX3688",
                "GSTIN : 19AAGCB1323G2ZZ",
                "Total Amount Due : Rs. 0.01"
        );
        Map<String, String> fields = LabeledFieldExtractor.extract(lines, Map.of(
                "statementDate", "Statement Date",
                "minimumAmountDue", "Minimum Amount Due",
                "paymentDueDate", "Payment Due Date",
                "totalAmountDue", "Total Amount Due"
        ));

        assertEquals("June 15, 2026", fields.get("statementDate"));
        assertEquals("Rs. 0.01", fields.get("minimumAmountDue"));
        assertEquals("June 17, 2026", fields.get("paymentDueDate"));
        assertEquals("Rs. 0.01", fields.get("totalAmountDue"));
    }

    @Test
    void extract_repeatedLabelLaterInLines_firstOccurrenceWins() {
        List<String> lines = List.of(
                "Total Amount Due : Rs. 0.01",
                "Cash Withdrawal Total Amount Due",
                "Total Amount Due on statement dated Dec 10 3000.00"
        );
        Map<String, String> fields = LabeledFieldExtractor.extract(lines, Map.of("totalAmountDue", "Total Amount Due"));
        assertEquals("Rs. 0.01", fields.get("totalAmountDue"));
    }

    @Test
    void extract_missingLabelIsAbsentFromResult() {
        List<String> lines = List.of("Statement Date: June 15, 2026");
        Map<String, String> fields = LabeledFieldExtractor.extract(lines, Map.of(
                "statementDate", "Statement Date",
                "totalAmountDue", "Total Amount Due"
        ));
        assertTrue(fields.containsKey("statementDate"));
        assertFalse(fields.containsKey("totalAmountDue"));
    }

    @Test
    void extract_labelValueOnNextLineVariant() {
        List<String> lines = List.of("Statement Date", "June 15, 2026", "Minimum Amount Due :", "Rs. 0.01");
        Map<String, String> fields = LabeledFieldExtractor.extract(lines, Map.of(
                "statementDate", "Statement Date",
                "minimumAmountDue", "Minimum Amount Due"
        ));
        assertEquals("June 15, 2026", fields.get("statementDate"));
        assertEquals("Rs. 0.01", fields.get("minimumAmountDue"));
    }

    @Test
    void extract_emptyInputsReturnEmptyMap() {
        assertTrue(LabeledFieldExtractor.extract(List.of(), Map.of("a", "A")).isEmpty());
        assertTrue(LabeledFieldExtractor.extract(List.of("A: 1"), Map.of()).isEmpty());
        assertTrue(LabeledFieldExtractor.extract(null, Map.of("a", "A")).isEmpty());
    }
}
