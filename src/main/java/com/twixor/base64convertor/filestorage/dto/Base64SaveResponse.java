package com.twixor.base64convertor.filestorage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Base64SaveResponse {
    private boolean success;
    private String message;
    private String fileName;
    private String downloadLink;
    private String fileSize;
    private String savedAt;
}
