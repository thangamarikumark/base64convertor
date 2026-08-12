package com.twixor.base64convertor.common.validation;

import java.util.Base64;

public class Base64FileValidator {

    /**
     * Validates that a Base64 string is correctly encoded
     * and matches its MIME type (based on magic bytes).
     *
     * @param base64   the Base64 string
     * @param mimeType the expected MIME type (e.g. image/jpeg, application/pdf)
     * @return true if valid Base64 and matches file signature
     */
    public static boolean isValidBase64ForMime(String base64, String mimeType) {
        if (base64 == null || base64.isBlank()) return false;

        // Pad to a multiple of 4 to support both padded and without-padding encoded strings.
        // Base64.getDecoder() requires proper padding; without-padding encoding omits trailing '='.
        byte[] decoded;
        try {
            int rem = base64.length() % 4;
            String padded = rem == 0 ? base64 : base64 + "=".repeat(4 - rem);
            decoded = Base64.getDecoder().decode(padded);
        } catch (IllegalArgumentException e) {
            return false;
        }

        return isValidDecodedForMime(decoded, mimeType);
    }

    /**
     * Same check as {@link #isValidBase64ForMime(String, String)}, but for callers that have
     * already decoded the content (e.g. because they needed the bytes for something else first)
     * — avoids decoding the same Base64 string a second time per request.
     *
     * @param decoded  the already-decoded file content
     * @param mimeType the expected MIME type (e.g. image/jpeg, application/pdf)
     * @return true if non-empty and matches the file signature
     */
    public static boolean isValidDecodedForMime(byte[] decoded, String mimeType) {
        if (decoded == null || decoded.length == 0) return false;
        return verifyFileSignature(decoded, mimeType);
    }

    /**
     * Checks file magic number (header bytes) against known MIME types.
     */
    private static boolean verifyFileSignature(byte[] data, String mimeType) {
        if (mimeType == null) return true; // skip type check if unknown

        mimeType = mimeType.toLowerCase();

        // === Image formats ===
        if (mimeType.contains("jpeg") || mimeType.contains("jpg")) {
            return data.length > 3 && data[0] == (byte) 0xFF && data[1] == (byte) 0xD8;
        }

        if (mimeType.contains("png")) {
            return data.length > 4 &&
                    data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
        }

        if (mimeType.contains("gif")) {
            return data.length > 3 &&
                    data[0] == 'G' && data[1] == 'I' && data[2] == 'F';
        }

        // === PDF ===
        if (mimeType.contains("pdf")) {
            return data.length > 4 &&
                    data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
        }

        // === ZIP / DOCX / XLSX / PPTX ===
        if (mimeType.contains("zip") || mimeType.contains("officedocument") ||
                mimeType.contains("excel") || mimeType.contains("powerpoint") ||
                mimeType.contains("word")) {
            return data.length > 3 && data[0] == 'P' && data[1] == 'K';
        }

        // === Plain text / JSON ===
        if (mimeType.contains("text") || mimeType.contains("json")) {
            // Check for readable ASCII start
            for (int i = 0; i < Math.min(data.length, 8); i++) {
                if (data[i] < 9 || data[i] > 126) return false;
            }
            return true;
        }

        // Unknown MIME — skip strict validation
        return true;
    }
}
