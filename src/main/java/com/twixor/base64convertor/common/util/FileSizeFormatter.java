package com.twixor.base64convertor.common.util;

/**
 * Shared human-readable file-size formatting (Phase A, A6). Extracted from two previously
 * duplicated inline implementations (FileRetrievalController, Base64DecodingService) —
 * algorithm and formatting copied verbatim.
 */
public final class FileSizeFormatter {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private FileSizeFormatter() {
    }

    public static String format(long bytes) {
        if (bytes <= 0) return "0 B";
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.2f %s", bytes / Math.pow(1024, digitGroups), UNITS[digitGroups]);
    }
}
