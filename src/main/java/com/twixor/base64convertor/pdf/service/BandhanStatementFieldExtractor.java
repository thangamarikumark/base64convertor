package com.twixor.base64convertor.pdf.service;

import com.twixor.base64convertor.common.util.LabeledFieldExtractor;
import com.twixor.base64convertor.pdf.config.BandhanFieldPatternsProperties;
import com.twixor.base64convertor.pdf.util.PdfTextExtractor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates statement-field extraction for {@code POST /api/files/convert/pdf/bandhan/protect}:
 * decoded PDF bytes -&gt; {@link PdfTextExtractor} -&gt; non-blank lines, scoped to the statement
 * header -&gt; configured labels ({@link BandhanFieldPatternsProperties}) -&gt;
 * {@link LabeledFieldExtractor}.
 *
 * <p>Contains no regex or field-specific logic of its own — line splitting/value matching is
 * delegated to {@link LabeledFieldExtractor}, and the only Bandhan-specific policy here is
 * <em>where</em> to look: real statement text mixes our four target labels with unrelated
 * fields (CIF, Primary Card Number, GSTIN) and, further down, the same labels reappearing as a
 * table heading and inside page-2 illustrative examples. Restricting the search to the first
 * {@link BandhanFieldPatternsProperties#getMaxScanLines()} non-blank lines, cut short at the
 * first {@link BandhanFieldPatternsProperties#getSectionBoundaryMarkers()} match, keeps
 * extraction confined to the statement header where the real values live — by construction,
 * not by the accident of which label happens to appear first in an unbounded search.
 *
 * <p>Field extraction is always best-effort: any failure to read the PDF's text is logged and
 * degrades to "no fields found" rather than propagating, because password protection (handled
 * separately by {@link BandhanProtectServiceImpl}) is this endpoint's primary responsibility.
 */
@Component
public class BandhanStatementFieldExtractor {

    private static final Logger log = LogManager.getLogger(BandhanStatementFieldExtractor.class);

    private final PdfTextExtractor pdfTextExtractor;
    private final BandhanFieldPatternsProperties fieldPatternsProperties;

    public BandhanStatementFieldExtractor(PdfTextExtractor pdfTextExtractor,
                                           BandhanFieldPatternsProperties fieldPatternsProperties) {
        this.pdfTextExtractor = pdfTextExtractor;
        this.fieldPatternsProperties = fieldPatternsProperties;
    }

    /**
     * @param pdfBytes decoded, already-validated PDF content
     * @return map of field name (e.g. {@code statementDate}) to extracted value, containing only
     *         the fields whose configured label was actually found within the scoped header
     *         section — never null, empty when extraction fails or nothing matches
     */
    public Map<String, String> extractFields(byte[] pdfBytes) {
        String text;
        try {
            text = pdfTextExtractor.extractText(pdfBytes);
            log.info("PDF text extracted for statement field extraction ({} characters)", text.length());
        } catch (IOException e) {
            log.warn("Statement field extraction skipped: PDF text could not be extracted ({})", e.getMessage());
            return Map.of();
        }

        List<String> scopedLines = scopeToStatementHeader(LabeledFieldExtractor.toNonBlankLines(text));
        log.info("Statement field extraction scoped to {} lines", scopedLines.size());

        Map<String, String> fields = LabeledFieldExtractor.extract(scopedLines, fieldPatternsProperties.getFieldLabels());
        log.info("Statement fields extracted: {}", fields.keySet());
        return fields;
    }

    /**
     * Restricts {@code lines} to the statement header near the top of page 1: capped at
     * {@code pdf.bandhan.max-scan-lines} non-blank lines, and cut short at (not including) the
     * first line matching any configured section-boundary marker.
     */
    private List<String> scopeToStatementHeader(List<String> lines) {
        int limit = Math.min(lines.size(), fieldPatternsProperties.getMaxScanLines());
        List<String> boundaryMarkers = fieldPatternsProperties.getSectionBoundaryMarkers();
        List<String> scoped = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String line = lines.get(i);
            if (isSectionBoundary(line, boundaryMarkers)) {
                break;
            }
            scoped.add(line);
        }
        return scoped;
    }

    private boolean isSectionBoundary(String line, List<String> boundaryMarkers) {
        if (boundaryMarkers == null) {
            return false;
        }
        for (String marker : boundaryMarkers) {
            if (marker != null && !marker.isBlank() && line.toLowerCase().contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
