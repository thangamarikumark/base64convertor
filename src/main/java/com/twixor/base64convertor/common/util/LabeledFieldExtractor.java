package com.twixor.base64convertor.common.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic line-based "label: value" text-field extractor. Given a list of already-scoped,
 * trimmed, non-blank lines and a map of {@code fieldName -> labelText}, finds the first line
 * containing each label and returns the value that follows it — either the remainder of that
 * same line (after stripping the label and an optional {@code :}/{@code -} separator) or, when
 * the label occupies its own line, the next non-blank line.
 *
 * <p>Has no knowledge of PDFs, Bandhan, bank statements, or any other specific caller — any
 * module that needs to pull labeled values out of a list of text lines can reuse this as-is.
 * Line splitting/scoping (which lines to search, how many, where to stop) is deliberately left
 * to the caller, since that policy is inherently document-specific — see
 * {@code com.twixor.base64convertor.pdf.service.BandhanStatementFieldExtractor} for an example
 * that scopes to the top of a bank statement's first page.
 *
 * <p>Handles all of the following, without any per-field code:
 * <ul>
 *   <li>{@code "Label: value"} / {@code "Label : value"} (colon with or without a leading space)</li>
 *   <li>{@code "Label"} alone on a line, value on the next line</li>
 *   <li>{@code "Label :"} alone on a line (trailing separator, no value), value on the next line</li>
 * </ul>
 *
 * <p>Value resolution only ever looks at the label's own line and the single line immediately
 * following it — it deliberately does not scan forward to "the next known label," which is what
 * made the previous whole-document implementation fragile whenever unrelated fields or a
 * repeated label sat between two target labels.
 */
public final class LabeledFieldExtractor {

    private LabeledFieldExtractor() {
    }

    /**
     * Splits {@code text} into trimmed, non-blank lines (CRLF/CR normalized to LF first).
     * Callers typically scope/truncate the result (e.g. to the first N lines, or up to a
     * section-boundary marker) before passing it to {@link #extract(List, Map)}.
     */
    public static List<String> toNonBlankLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String trimmed = rawLine.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /**
     * @return the index of the first line (scanning from index 0) that contains {@code label}
     *         as a case-insensitive substring, or empty if not found
     */
    public static Optional<Integer> extractFirstOccurrence(List<String> lines, String label) {
        if (lines == null || lines.isEmpty() || label == null || label.isBlank()) {
            return Optional.empty();
        }
        Pattern pattern = Pattern.compile(Pattern.quote(label.trim()), Pattern.CASE_INSENSITIVE);
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find()) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    /**
     * Given a line already known to contain {@code label}, returns the trimmed text that
     * follows it on that same line, with a leading separator ({@code :}, {@code -}, or
     * whitespace) stripped.
     *
     * @return the same-line value, or empty if nothing meaningful follows the label on this
     *         line (the label is alone on the line, optionally with a trailing separator only —
     *         e.g. {@code "Statement Date"} or {@code "Statement Date :"})
     */
    public static Optional<String> extractAfterLabel(String line, String label) {
        if (line == null || label == null || label.isBlank()) {
            return Optional.empty();
        }
        Pattern pattern = Pattern.compile(Pattern.quote(label.trim()), Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String remainder = stripLeadingSeparators(line.substring(matcher.end())).trim();
        return remainder.isEmpty() ? Optional.empty() : Optional.of(remainder);
    }

    /**
     * Resolves a label's value: the remainder of {@code lines.get(labelLineIndex)} after the
     * label if {@link #extractAfterLabel} finds one, otherwise the very next line in
     * {@code lines} (the label occupies its own line, so the value is on the line that follows).
     */
    public static Optional<String> extractValueFromSameOrNextLine(List<String> lines, int labelLineIndex, String label) {
        if (lines == null || labelLineIndex < 0 || labelLineIndex >= lines.size()) {
            return Optional.empty();
        }
        Optional<String> sameLine = extractAfterLabel(lines.get(labelLineIndex), label);
        if (sameLine.isPresent()) {
            return sameLine;
        }
        int nextIndex = labelLineIndex + 1;
        return nextIndex < lines.size() ? Optional.of(lines.get(nextIndex)) : Optional.empty();
    }

    /**
     * @param lines       already-scoped, trimmed, non-blank lines to search (see
     *                    {@link #toNonBlankLines(String)} and caller-side scoping)
     * @param fieldLabels map of caller-defined field name to the label text to search for
     * @return map of field name to extracted value, containing only the fields whose label was
     *         found in {@code lines} with a resolvable value — never null, empty when nothing
     *         matches
     */
    public static Map<String, String> extract(List<String> lines, Map<String, String> fieldLabels) {
        if (lines == null || lines.isEmpty() || fieldLabels == null || fieldLabels.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fieldLabels.entrySet()) {
            String label = entry.getValue();
            if (label == null || label.isBlank()) {
                continue;
            }
            extractFirstOccurrence(lines, label)
                    .flatMap(lineIndex -> extractValueFromSameOrNextLine(lines, lineIndex, label))
                    .ifPresent(value -> result.put(entry.getKey(), value));
        }
        return result;
    }

    /** Strips a leading separator (":", "-", "\u2013", "\u2014") and any surrounding whitespace. */
    private static String stripLeadingSeparators(String value) {
        return value.replaceFirst("^[\\s:\\-\u2013\u2014]+", "");
    }
}
