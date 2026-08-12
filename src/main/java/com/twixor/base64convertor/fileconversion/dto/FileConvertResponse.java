package com.twixor.base64convertor.fileconversion.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileConvertResponse {
    private String processingId;   // NEW: unique ID for async tracking
    private String fileName;
    private String mimeType;
    private String type;
    private String caption;
    private String base64Data;     // may be null if still processing
    private boolean success;
    private String message;
}

