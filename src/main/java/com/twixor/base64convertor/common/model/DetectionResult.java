package com.twixor.base64convertor.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

/**
 * Result of MIME-type/extension detection performed by {@code FileTypeDetectionService}.
 * Promoted from a public static inner class to a top-level model (Phase A, A9) — same
 * fields and builder API as before, now backed by Lombok instead of a hand-rolled builder.
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectionResult {
    public String mimeType;
    public String extension;
    public boolean success;
    public String error;
}
