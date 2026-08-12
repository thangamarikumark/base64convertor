package com.twixor.base64convertor.common.util;

/**
 * Shared filename-sanitization logic (Phase A, A5). Extracted from three previously
 * duplicated inline implementations (FileRetrievalController, Base64DecodingService,
 * Base64OutputWriter) — the regex and fallback behavior are copied verbatim.
 */
public final class FileNameSanitizer {

    private FileNameSanitizer() {
    }

    /**
     * Replaces any character outside {@code [a-zA-Z0-9._-]} with {@code _}.
     * Returns {@code fallback} if the input is null/blank.
     */
    public static String sanitize(String fileName, String fallback) {
        if (fileName == null || fileName.isBlank()) {
            return fallback;
        }
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
