package com.twixor.base64convertor.fileconversion.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FileConvertRequest {

    @NotBlank(message = "URL must not be empty")
    private String url;

    private String fileName;
    private String caption;

    @NotBlank(message = "mimeType is required")
    private String mimeType;

    @NotBlank(message = "type is required")
    private String type;
}
