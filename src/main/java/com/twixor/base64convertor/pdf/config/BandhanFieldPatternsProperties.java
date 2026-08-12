package com.twixor.base64convertor.pdf.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for the statement-field labels searched for by
 * {@link com.twixor.base64convertor.pdf.service.BandhanStatementFieldExtractor}, bound to the
 * {@code pdf.bandhan.*} prefix (module-local config, same pattern as
 * {@link PdfProtectionProperties} — see ADR-004).
 *
 * <p>Labels are the only thing that differs between statement layouts, so they live here rather
 * than in code: adding support for a new field (e.g. "Customer Name", "Policy Number") is a
 * {@code pdf.bandhan.field-labels.<fieldName>=<Label Text>} properties-file addition, not a
 * Java code change — see docs/Configuration.md conventions.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "pdf.bandhan")
public class BandhanFieldPatternsProperties {

    /**
     * Map of response field name to the label text to search for in the extracted PDF text
     * (e.g. {@code statementDate -> "Statement Date"}). Pre-populated with the four fields
     * {@link com.twixor.base64convertor.pdf.dto.BandhanProtectResponse} currently exposes;
     * Spring Boot's relaxed Map binding merges any {@code pdf.bandhan.field-labels.*} properties
     * into this map (overriding an existing key or adding a new one) rather than replacing it
     * wholesale, so these defaults keep working even when only some labels are overridden.
     */
    private Map<String, String> fieldLabels = new LinkedHashMap<>(Map.of(
            "statementDate", "Statement Date",
            "totalAmountDue", "Total Amount Due",
            "minimumAmountDue", "Minimum Amount Due",
            "paymentDueDate", "Payment Due Date"
    ));

    /**
     * Maximum number of non-blank lines, counted from the top of the extracted PDF text, that
     * {@link com.twixor.base64convertor.pdf.service.BandhanStatementFieldExtractor} will search
     * for labels. Keeps extraction confined to the statement header near the top of page 1 —
     * without this cap, a label that legitimately repeats later in the document (a transaction
     * table heading, a page-2 illustrative example) could be matched instead of the real value.
     */
    private int maxScanLines = 30;

    /**
     * Line content (case-insensitive substring match) that marks the end of the statement
     * header section — once a scanned line contains any of these markers, scanning stops even
     * if {@link #maxScanLines} hasn't been reached yet. Configurable so a different statement
     * layout (a different section title, or none at all) needs only a properties change.
     */
    private List<String> sectionBoundaryMarkers = new ArrayList<>(List.of("Statement Summary"));
}
