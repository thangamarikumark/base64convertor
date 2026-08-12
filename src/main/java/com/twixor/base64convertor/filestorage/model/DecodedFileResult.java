package com.twixor.base64convertor.filestorage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

/**
 * Result of decoding+saving a Base64 payload, produced by {@code Base64DecodingService}.
 * Promoted from a public static inner class to a top-level model (Phase A, A9) — same
 * fields and builder API as before.
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecodedFileResult {
    public String fileName;
    public String originalFileName;
    public String fileSize;
    public long fileSizeBytes;
    public String mimeType;
    public String downloadLink;
    public String metadataFile;
    public String savedAt;
}
