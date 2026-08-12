package com.twixor.base64convertor.filestorage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Listing entry for a single decoded file (POST /save-decoded output), returned by
 * GET /api/files/convert/list-decoded. Mirrors {@link FileInfo} (the equivalent listing
 * entry for .b64 files) field-for-field in style, plus the two fields specific to decoded
 * files: the Tika-detected MIME type recorded in the .meta.json sidecar at save time, and
 * the expiry date derived from app.decoded-file.retention-days.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DecodedFileInfo {
    private String fileName;
    private long size;
    private String createdDate;
    private String mimeType;
    private String expiryDate;
}
