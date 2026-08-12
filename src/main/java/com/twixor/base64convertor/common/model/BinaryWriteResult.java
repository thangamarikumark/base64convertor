package com.twixor.base64convertor.common.model;

import lombok.Builder;

/**
 * Result of a binary write + metadata sidecar write performed by {@code Base64OutputWriter}.
 * Promoted from a public static inner class to a top-level model (Phase A, A9) — same
 * fields and builder API as before.
 */
@Builder
public class BinaryWriteResult {
    public final String fileName;
    public final String metadataFile;
    public final String downloadLink;
    public final String fileSize;
    public final long fileSizeBytes;
    public final String savedAt;
}
