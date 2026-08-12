package com.twixor.base64convertor.filestorage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DecodedFileSaveResponse {
    private boolean success;
    private String message;
    private String fileName;
    private String originalFileName;
    private String downloadLink;
    private String fileSize;
    private long fileSizeBytes;
    private String mimeType;
    private String metadataFile;
    private String savedAt;
}
